package ai.starlake.acl.parser

import java.lang.reflect.Method

/** The reflective child access every fail-closed JSqlParser walk shares (the ACL table walk, the
  * RLS rewrite walk, the CLS residual check), so they agree on what a child is.
  *
  * A walk routes each child by its RUNTIME type and must treat a value it has no rule for as a
  * refusal, never skip it: JSqlParser keeps some children outside its own node types (a MAP
  * literal's entries are `java.util.Map.Entry` pairs, a JSON key/value pair types both halves as
  * `Object`), and a subquery held there is invisible to a walk that only follows AST nodes.
  */
object AstChildren:

  private val accessorCache =
    new java.util.concurrent.ConcurrentHashMap[Class[?], Array[Method]]()

  /** The no-arg `get*` accessors of `cls` that can reach a child: return type is a collection
    * (`Iterable`), a `java.util.Map`, an array, `Object` (`JsonKeyValuePair.getKey`,
    * `Function.getAttribute`), or any JSqlParser AST type outside the `net.sf.jsqlparser.parser`
    * package (excluded so a walk never follows an upward parent link into a cycle). Primitives,
    * String and other library types are excluded, so leaves cost only the memoized lookup.
    */
  def accessors(cls: Class[?]): Array[Method] =
    accessorCache.computeIfAbsent(
      cls,
      c =>
        c.getMethods.filter { m =>
          val rt = m.getReturnType
          m.getParameterCount == 0 &&
          m.getName.startsWith("get") &&
          (classOf[java.lang.Iterable[?]].isAssignableFrom(rt) ||
            classOf[java.util.Map[?, ?]].isAssignableFrom(rt) ||
            rt.isArray ||
            rt == classOf[Object] ||
            isAstType(rt))
        }
    )

  /** One accessor's value, or null when the getter throws: some JSqlParser getters cast `this`
    * (`Select.getPlainSelect` on a set operation), so the throw means "not this subtype", and the
    * node itself is reached through its other accessors.
    */
  def read(m: Method, node: AnyRef): Any =
    try m.invoke(node)
    catch case scala.util.control.NonFatal(_) => null

  def isAstType(rt: Class[?]): Boolean =
    val n = rt.getName
    n.startsWith("net.sf.jsqlparser.") && !n.startsWith("net.sf.jsqlparser.parser.")

  def isAstNode(value: AnyRef): Boolean = isAstType(value.getClass)

  /** True for a value that holds no AST child: names, numbers, flags, characters, enum constants,
    * dates, class objects, and the parser's own token / node plumbing (never a statement part).
    * Anything else a walk meets and has no rule for is unknown and must be refused.
    */
  def isLeaf(value: Any): Boolean =
    value match
      case _: String | _: java.lang.Number | _: java.lang.Boolean | _: java.lang.Character |
          _: java.lang.Enum[?] | _: Class[?] | _: java.util.Date |
          _: java.time.temporal.TemporalAccessor =>
        true
      case v: AnyRef => v.getClass.getName.startsWith("net.sf.jsqlparser.parser.")
      case _         => false
