package ai.starlake.sql

/** A statement nested deeply enough (`SELECT 1+1+...+1`, `(SELECT (SELECT ...))`) overflows the
  * recursive walks that analyse it: JSqlParser's own printers and visitors as much as QoD's. A
  * `StackOverflowError` is fatal to cats-effect, so one escaping a walk halted the whole manager
  * under `IOApp`, and no `IO.attempt` / `handleError` can stop it: the runtime treats the error as
  * fatal before any handler runs.
  *
  * Every analysis entry point on the statement path therefore catches it synchronously, on the
  * thread that overflowed, and answers its own fail-closed outcome. The stack is unwound by then
  * and a walk's state is local to its call, so nothing is left half-updated. Inside an `IO`, the
  * catch must sit inside the closure that recurses, never around the `IO` value.
  */
object TooDeep:

  val Reason = "statement is nested too deeply to analyse; denied (fail-closed)"

  /** `body`, or `tooDeep` when `body` overflows the thread's stack. */
  def orElse[A](tooDeep: => A)(body: => A): A =
    try body
    catch case _: StackOverflowError => tooDeep
