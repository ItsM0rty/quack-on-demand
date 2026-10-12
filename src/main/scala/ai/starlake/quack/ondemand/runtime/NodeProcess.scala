package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.RunningNode

import java.time.{Duration, Instant}

/** The live OS process a pid-carrying (local) node row names, verified by identity.
  *
  * A row's pid only means something in the pid namespace and boot that wrote it: after a manager or
  * container restart the same number can belong to an unrelated process (another pool's node, an
  * embedded Postgres backend). A pid is taken as the node's only when the process's own start
  * instant matches the row's `startedAt`, which the local backend stamps right after the fork. The
  * handle returned also carries that start time, so signalling it can never reach a process that
  * reuses the pid later.
  */
object NodeProcess:

  /** Tolerance between the OS-reported start instant and `startedAt`. Linux derives the start
    * instant from a whole-second boot time plus clock ticks, so the two can differ by about a
    * second; a reused pid starts well after the row's node did.
    */
  val StartSlack: Duration = Duration.ofSeconds(5)

  /** The live process `node` names, or None when its pid is absent, dead, its start instant is
    * unavailable, or that instant does not match `node.startedAt`.
    */
  def of(node: RunningNode): Option[ProcessHandle] =
    node.pid.flatMap(pid => of(pid, node.startedAt))

  def of(pid: Long, startedAt: Instant): Option[ProcessHandle] =
    val h = ProcessHandle.of(pid)
    if !h.isPresent || !h.get().isAlive then None
    else
      val started = h.get().info().startInstant()
      if started.isPresent && matches(started.get(), startedAt) then Some(h.get()) else None

  private def matches(processStart: Instant, startedAt: Instant): Boolean =
    !processStart.isBefore(startedAt.minus(StartSlack)) &&
      !processStart.isAfter(startedAt.plus(StartSlack))
