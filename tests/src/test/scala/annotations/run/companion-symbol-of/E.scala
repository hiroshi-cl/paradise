// if this file compiles, then we're good
package e

class foo(x: Any) extends annotation.StaticAnnotation

@foo(new AnyRef {
  class bar extends annotation.StaticAnnotation

  @bar def baz = ???
}) trait A
