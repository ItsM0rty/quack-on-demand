package ai.starlake.sql

/** The one reading of a quoted identifier shared by every site that matches a parsed name against a
  * policy, a grant or a function list.
  *
  * DuckDB 2.0 quotes an identifier with double quotes or, new in 2.0, with backticks, each doubling
  * its own delimiter inside (`"a""b"` and `` `a``b` `` are both `a"b` / `` a`b ``, and `` `a"b` ``
  * is the same identifier as `"a""b"`, checked on the 2.0 CLI). An unquote that knows only one of
  * the two leaves the delimiters of the other on the name, so the name matches nothing: a policy
  * keyed on `orders` never saw `` `orders` ``, while JSqlParser's `getUnquotedName` (the ACL's
  * reading) strips both and authorized the read.
  */
private[starlake] object QuotedIdentifier:

  /** The name `s` stands for: the body of a double-quoted or backtick-quoted identifier with its
    * delimiter undoubled, or `s` itself when it is not one quoted identifier.
    */
  def unquote(s: String): String =
    if s == null || s.length < 2 then s
    else
      val d = s.charAt(0)
      if (d == '"' || d == '`') && s.charAt(s.length - 1) == d then
        s.substring(1, s.length - 1).replace(s"$d$d", d.toString)
      else s

  /** `name` as one double-quoted identifier, its double quotes doubled. */
  def doubleQuoted(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
