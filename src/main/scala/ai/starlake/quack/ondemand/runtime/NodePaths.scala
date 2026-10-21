package ai.starlake.quack.ondemand.runtime

import java.io.File
import java.nio.file.Paths

/** A local data path as a node resolves it. The spawn script starts duckdb in an empty directory of
  * its own, after resolving a relative local dataPath against the directory it started in, which
  * for a local node is this process's working directory. A path the manager writes for a node to
  * read (a branch's parent file paths, the merge node's branch ATTACH) must be resolved the same
  * way, or it lands inside that empty directory. On a fleet server or a pod a relative path names a
  * different directory anyway.
  */
object NodePaths:
  private val SchemeRe = "^[a-zA-Z][a-zA-Z0-9+.-]*://.*$".r

  /** A non-empty local path that is not absolute (an object-store URL never is local). */
  def isRelativeLocal(path: String): Boolean =
    path.nonEmpty && !SchemeRe.matches(path) && !Paths.get(path).isAbsolute

  /** `path` resolved against this process's working directory when [[isRelativeLocal]], keeping a
    * trailing separator; any other path unchanged.
    */
  def resolve(path: String): String =
    if !isRelativeLocal(path) then path
    else
      val abs = Paths.get(path).toAbsolutePath.normalize.toString
      if path.endsWith("/") || path.endsWith(File.separator) then abs + File.separator else abs
