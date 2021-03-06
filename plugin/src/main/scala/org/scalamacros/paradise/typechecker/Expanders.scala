package org.scalamacros.paradise
package typechecker

trait Expanders {
  self: AnalyzerPlugins =>

  import scala.{Seq => _}
  import scala.collection.immutable.Seq
  import scala.util.control.ControlThrowable
  import global._
  import analyzer._
  import ErrorUtils._
  import definitions._
  import scala.reflect.internal.Flags._
  import scala.reflect.internal.Mode._
  import scala.reflect.runtime.ReflectionUtils
  import analyzer.{Namer => NscNamer}
  import scala.meta.{Input => MetaInput, Position => MetaPosition}
  import scala.meta.{Tree => MetaTree, Source => MetaSource, Transformer => MetaTransformer}
  import scala.meta.internal.prettyprinters.{Positions => MetaPositions}

  def mkExpander(namer0: NscNamer) = new { val namer: NscNamer = namer0 } with Namer with Expander
  trait Expander {
    self: Namer with Expander =>

    val namer: NscNamer
    import namer._
    val expanderErrorGen = new ErrorGen(namer.typer)
    import expanderErrorGen._
    import namer.typer.TyperErrorGen._

    def expandOldAnnotationMacro(original: Tree, annotationSym: Symbol, annotationTree: Tree, expandees: List[Tree]): Option[List[Tree]] = {
      def onlyIfExpansionAllowed[T](expand: => Option[T]): Option[T] = {
        if (settings.Ymacroexpand.value == settings.MacroExpand.None) None
        else {
          val oldYmacroexpand = settings.Ymacroexpand.value
          try { settings.Ymacroexpand.value = settings.MacroExpand.Normal; expand }
          catch { case ex: Exception => settings.Ymacroexpand.value = oldYmacroexpand; throw ex }
        }
      }
      def expand(): Option[Tree] = {
        def rollThroughImports(context: Context): Context = {
          if (context.isInstanceOf[ImportContext]) rollThroughImports(context.outer)
          else context
        }
        val typer = {
          // expanding at top level => allow the macro to see everything
          if (original.symbol.isTopLevel) newTyper(context)
          // expanding at template level => only allow to see outside of the enclosing class
          // we have to skip two contexts:
          //  1) the Template context that hosts members
          //  2) the ImplDef context that hosts type params (and just them?)
          // upd. actually, i don't think we should skip the second context
          // that doesn't buy us absolutely anything wrt robustness
          else if (original.symbol.owner.isClass) newTyper(rollThroughImports(context).outer)
          // expanding at block level => only allow to see outside of the block
          else newTyper(rollThroughImports(context).outer)
        }
        val expandee = {
          val annotationMacroSym = annotationSym.info.member(nme.macroTransform)
          val prefix = Select(annotationTree, nme.macroTransform) setSymbol annotationMacroSym setPos annotationTree.pos
          Apply(prefix, expandees) setPos annotationTree.pos
        }
        (new DefMacroExpander(typer, expandee, NOmode, WildcardType) {
          override def onSuccess(expanded: Tree) = expanded
        })(expandee) match {
          case tree if tree.isErroneous => None
          case tree => Some(tree)
        }
      }
      extractAndValidateExpansions(original, annotationTree, () => onlyIfExpansionAllowed(expand()))
    }

    // TODO: a full-fledged reflect <-> meta converter is necessary for robust operation here
    def expandNewAnnotationMacro(original: Tree, annotationSym: Symbol, annotationTree: Tree, expandees: List[Tree]): Option[List[Tree]] = {
      def expand(): Option[Tree] = {
        try {
          val input = original.pos.source
          val metaInput = {
            if (input.file.file != null) MetaInput.File(input.file.file)
            else MetaInput.String(new String(input.content)) // NOTE: can happen in REPL or in custom Global
          }
          val metaSource = metaInput.parse[MetaSource].get
          def toMeta(tree: Tree): MetaTree = {
            var minTree: MetaTree = null
            def captures(metaPos: MetaPosition, pos: Position) = metaPos.start.offset <= pos.point && pos.point <= metaPos.end.offset
            def updatesMin(metaPos: MetaPosition, minPos: MetaPosition) = metaPos.end.offset - metaPos.start.offset < minPos.end.offset - minPos.start.offset
            metaSource.traverse {
              case metaTree: MetaTree if metaTree != metaSource && metaTree.is[scala.meta.Defn] && captures(metaTree.pos, tree.pos) && (minTree == null || updatesMin(metaTree.pos, minTree.pos)) =>
                minTree = metaTree
            }
            if(minTree == null)
              sys.error(s"fatal error: couldn't find ${tree.pos.toString} in ${metaSource.show[MetaPositions]}")
            minTree
          }

          val treeInfo.Applied(Select(New(_), nme.CONSTRUCTOR), targs, vargss) = annotationTree
          val metaTargs = targs.map(toMeta)
          val metaVargss = vargss.map(_.map(toMeta))
          val metaExpandees = {
            if (expandees.length != 1) sys.error("fatal error: multiple expandees not supported at the moment")
            val metaOriginal = toMeta(original)
            val metaOriginalWithoutAnnots = metaOriginal.transform {
              // TODO: detect and remove just annotteeTree
              case defn: scala.meta.Decl.Val => defn.copy(mods = Nil)
              case defn: scala.meta.Decl.Var => defn.copy(mods = Nil)
              case defn: scala.meta.Decl.Def => defn.copy(mods = Nil)
              case defn: scala.meta.Decl.Type => defn.copy(mods = Nil)
              case defn: scala.meta.Defn.Val => defn.copy(mods = Nil)
              case defn: scala.meta.Defn.Var => defn.copy(mods = Nil)
              case defn: scala.meta.Defn.Def => defn.copy(mods = Nil)
              case defn: scala.meta.Defn.Macro => defn.copy(mods = Nil)
              case defn: scala.meta.Defn.Type => defn.copy(mods = Nil)
              case defn: scala.meta.Defn.Class => defn.copy(mods = Nil)
              case defn: scala.meta.Defn.Trait => defn.copy(mods = Nil)
              case defn: scala.meta.Defn.Object => defn.copy(mods = Nil)
            }
            List(metaOriginalWithoutAnnots)
          }
          val metaArgs = metaTargs ++ metaVargss.flatten ++ metaExpandees

          val classloader = {
            val m_findMacroClassLoader = analyzer.getClass.getMethods().find(_.getName == "findMacroClassLoader").get
            m_findMacroClassLoader.setAccessible(true)
            m_findMacroClassLoader.invoke(analyzer).asInstanceOf[ClassLoader]
          }
          val annotationModuleClass = {
            try Class.forName(annotationSym.fullName + "$impl$", true, classloader)
            catch {
              case ex: Throwable =>
              issueNormalTypeError(annotationTree, MacroAnnotationNotExpandedMessage)(namer.context)
              throw MacroExpansionException
            }
          }
          val annotationModule = annotationModuleClass.getField("MODULE$").get(null)
          val newStyleMacroMeth = annotationModuleClass.getDeclaredMethods().find(_.getName == "apply$impl").get
          newStyleMacroMeth.setAccessible(true)
          val metaExpansion = {
            // NOTE: this method is here for correct stacktrace unwrapping
            def macroExpandWithRuntime() = {
              try newStyleMacroMeth.invoke(annotationModule, metaArgs.asInstanceOf[List[AnyRef]].toArray: _*).asInstanceOf[MetaTree]
              catch {
                case ex: Throwable =>
                  val realex = ReflectionUtils.unwrapThrowable(ex)
                  realex match {
                    case ex: ControlThrowable => throw ex
                    case _ => MacroGeneratedException(annotationTree, realex)
                  }
              }
            }
            macroExpandWithRuntime()
          }

          val stringExpansion = metaExpansion.toString
          val parser = newUnitParser(new CompilationUnit(newSourceFile(stringExpansion, "<macro>")))
          Some(gen.mkTreeOrBlock(parser.parseStatsOrPackages()))
        } catch {
          // NOTE: this means an error that has been caught and reported
          case MacroExpansionException => None
        }
      }
      extractAndValidateExpansions(original, annotationTree, () => expand())
    }

    private def extractAndValidateExpansions(original: Tree, annotation: Tree, computeExpansion: () => Option[Tree]): Option[List[Tree]] = {
      val sym = original.symbol
      val companion = if (original.isInstanceOf[ClassDef]) patchedCompanionSymbolOf(sym, context) else NoSymbol
      val wasWeak = isWeak(companion)
      val wasTransient = companion == NoSymbol || companion.isSynthetic
      def extract(expanded: Tree): List[Tree] = expanded match {
        case Block(stats, Literal(Constant(()))) => stats // ugh
        case tree => List(tree)
      }
      def validate(expanded: List[Tree]): Option[List[Tree]] = {
        if (sym.owner.isPackageClass) {
          original match {
            case ClassDef(_, originalName, _, _) =>
              expanded match {
                case (expandedClass @ ClassDef(_, className, _, _)) :: Nil
                if className == originalName && wasWeak =>
                  attachExpansion(sym, List(expandedClass))
                  attachExpansion(companion, Nil)
                  Some(expanded)
                case (expandedCompanion @ ModuleDef(_, moduleName, _)) :: (expandedClass @ ClassDef(_, className, _, _)) :: Nil
                if className == originalName && moduleName == originalName.toTermName =>
                  attachExpansion(sym, if (wasWeak) List(expandedClass, expandedCompanion) else List(expandedClass))
                  attachExpansion(companion, List(expandedCompanion))
                  Some(expanded)
                case (expandedClass @ ClassDef(_, className, _, _)) :: (expandedCompanion @ ModuleDef(_, moduleName, _)) :: Nil
                if className == originalName && moduleName == originalName.toTermName =>
                  attachExpansion(sym, if (wasWeak) List(expandedClass, expandedCompanion) else List(expandedClass))
                  attachExpansion(companion, List(expandedCompanion))
                  Some(expanded)
                case _ =>
                  if (wasWeak) MacroAnnotationTopLevelClassWithoutCompanionBadExpansion(annotation)
                  else MacroAnnotationTopLevelClassWithCompanionBadExpansion(annotation)
                  None
              }
            case ModuleDef(_, originalName, _) =>
              expanded match {
                case (expandedModule @ ModuleDef(_, expandedName, _)) :: Nil if expandedName == originalName =>
                  attachExpansion(sym, List(expandedModule))
                  Some(expanded)
                case _ =>
                  MacroAnnotationTopLevelModuleBadExpansion(annotation)
                  None
              }
          }
        } else {
          if (wasTransient) {
            attachExpansion(sym, expanded)
            attachExpansion(companion, Nil)
          } else {
            def companionRelated(tree: Tree) = tree.isInstanceOf[ModuleDef] && tree.asInstanceOf[ModuleDef].name == companion.name
            val (forCompanion, forSym) = expanded.partition(companionRelated)
            attachExpansion(sym, forSym)
            attachExpansion(companion, forCompanion)
          }
          Some(expanded)
        }
      }
      for {
        lowlevelExpansion <- computeExpansion()
        expansion <- Some(extract(lowlevelExpansion))
        duplicated = expansion.map(duplicateAndKeepPositions)
        validatedExpansion <- validate(duplicated)
      } yield validatedExpansion
    }

    def expandMacroAnnotations(stats: List[Tree]): List[Tree] = {
      def mightNeedTransform(stat: Tree): Boolean = stat match {
        case stat: DocDef => mightNeedTransform(stat.definition)
        case stat: MemberDef => isMaybeExpandee(stat.symbol) || hasAttachedExpansion(stat.symbol)
        case _ => false
      }
      def rewrapAfterTransform(stat: Tree, transformed: List[Tree]): List[Tree] = (stat, transformed) match {
        case (stat @ DocDef(comment, _), List(transformed: MemberDef)) => List(treeCopy.DocDef(stat, comment, transformed))
        case (stat @ DocDef(comment, _), List(transformed: DocDef)) => List(transformed)
        case (_, Nil | List(_: MemberDef)) => transformed
        case (_, unexpected) => unexpected // NOTE: who knows how people are already using macro annotations, so it's scary to fail here
      }
      if (phase.id > currentRun.typerPhase.id || !stats.exists(mightNeedTransform)) stats
      else stats.flatMap(stat => {
        if (mightNeedTransform(stat)) {
          val sym = stat.symbol
          assert(sym != NoSymbol, (sym, stat))
          if (isMaybeExpandee(sym)) {
            def assert(what: Boolean) = Predef.assert(what, s"${sym.accurateKindString} ${sym.rawname}#${sym.id} with ${sym.rawInfo.kind}")
            assert(sym.rawInfo.isInstanceOf[Namer#MaybeExpandeeCompleter])
            sym.rawInfo.completeOnlyExpansions(sym)
            assert(!sym.rawInfo.isInstanceOf[Namer#MaybeExpandeeCompleter])
          }
          val derivedTrees = attachedExpansion(sym).getOrElse(List(stat))
          val (me, others) = derivedTrees.partition(_.symbol == sym)
          rewrapAfterTransform(stat, me) ++ expandMacroAnnotations(others)
        } else {
          List(stat)
        }
      })
    }
  }
}
