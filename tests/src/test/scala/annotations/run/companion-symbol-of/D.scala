// if this file compiles, then we're good
package d

class foo(x: Any) extends annotation.StaticAnnotation

class bar extends annotation.StaticAnnotation

@foo(new AnyRef {@bar def baz = ??? }) trait A
