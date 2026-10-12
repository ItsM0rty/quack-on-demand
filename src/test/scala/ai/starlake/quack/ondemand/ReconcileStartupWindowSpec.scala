package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.runtime.{LocalQuackBackend, LocalQuackBackendSpec, QuackBackend}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Regression for the pool-create spawn race: the local backend returns at fork time, so a node row
  * exists while its process is alive but not yet listening. A reconcile tick in that window used to
  * read the node as dead and respawn it under the same id without stopping the first process, which
  * then ran untracked (holding its port) past every stop path and manager shutdown.
  *
  * Real child processes (`sleep`, never listening) stand in for a node that is slow to listen; the
  * reconcile loop ticks far faster than the node could ever come up.
  */
class ReconcileStartupWindowSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_default", "sales")

  /** The real local backend, recording every node it starts. */
  private final class RecordingLocal(min: Int, max: Int) extends QuackBackend:
    private val inner = new LocalQuackBackend(
      min,
      max,
      commandFor = (_, _, _) => LocalQuackBackendSpec.sleepCmd(60)
    )
    val started                                = new ConcurrentLinkedQueue[RunningNode]()
    def start(spec: NodeSpec): IO[RunningNode] = inner.start(spec).flatTap(n => IO(started.add(n)))
    def stop(k: PoolKey, id: String): IO[Unit] = inner.stop(k, id)
    def isAlive(id: String): Boolean           = inner.isAlive(id)
    def discoverExisting(): IO[List[RunningNode]] = inner.discoverExisting()
    def cleanup(): IO[Unit]                       = inner.cleanup()
    override def adopt(n: RunningNode): IO[Unit]  = inner.adopt(n)

  private def alive(n: RunningNode): Boolean =
    n.pid.flatMap(p => Option(java.lang.ProcessHandle.of(p).orElse(null))).exists(_.isAlive)

  private def kill(n: RunningNode): Unit =
    n.pid.flatMap(p => Option(java.lang.ProcessHandle.of(p).orElse(null))).foreach { h =>
      h.descendants().forEach { d => d.destroyForcibly(); () }
      h.destroyForcibly()
      ()
    }

  private def withPool(min: Int, max: Int, grace: FiniteDuration)(
      body: (PoolSupervisor, RecordingLocal) => Unit
  ): Unit =
    val backend = new RecordingLocal(min, max)
    val sup     = new PoolSupervisor(
      backend,
      new NodeLoadTracker,
      new InMemoryControlPlaneStore(),
      nodeStartupGrace = grace
    )
    try
      sup.createTenant(Tenant("acme")).unsafeRunSync()
      sup
        .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
        .unsafeRunSync()
      sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
      body(sup, backend)
    finally
      backend.cleanup().unsafeRunSync()
      backend.started.asScala.foreach(kill)

  /** Run the reconcile loop every 20 ms for `forMs`, then stop it. */
  private def tick(sup: PoolSupervisor, forMs: Long): Unit =
    val fiber = sup.reconcileLoop(20.millis).start.unsafeRunSync()
    Thread.sleep(forMs)
    fiber.cancel.unsafeRunSync()

  "reconcile" should "not respawn a local node that is alive but still inside its startup window" in:
    assume(!LocalQuackBackendSpec.isWindows)
    withPool(23140, 23149, grace = 1.minute) { (sup, backend) =>
      val first = backend.started.asScala.toList
      first should have size 1
      tick(sup, 600)
      withClue("starts after ~30 reconcile ticks of a node still starting: ") {
        backend.started.asScala.toList shouldBe first
      }
      sup.get(poolKey).get.nodes.flatMap(_.pid) shouldBe first.flatMap(_.pid)
      alive(first.head) shouldBe true
    }

  it should "stop the old process before respawning a node past its startup window" in:
    assume(!LocalQuackBackendSpec.isWindows)
    withPool(23150, 23152, grace = 200.millis) { (sup, backend) =>
      // Each pass after the window finds the never-listening node dead and replaces it.
      val deadline = System.currentTimeMillis() + 6000
      val fiber    = sup.reconcileLoop(20.millis).start.unsafeRunSync()
      while backend.started.size < 5 && System.currentTimeMillis() < deadline do Thread.sleep(20)
      fiber.cancel.unsafeRunSync()

      val all = backend.started.asScala.toList
      all.size should be >= 2
      all.map(_.nodeId).distinct shouldBe List(PoolSupervisor.nodeId(poolKey, 1))
      val tracked = sup.get(poolKey).get.nodes.flatMap(_.pid).toSet
      tracked should have size 1
      val untracked = all.filterNot(n => n.pid.exists(tracked.contains))
      withClue(
        s"replaced nodes still running untracked: ${untracked.filter(alive).flatMap(_.pid)}"
      ) {
        untracked.filter(alive) shouldBe empty
      }
      // A range of three ports: a lease leaked per replacement would refuse the fourth start.
      all.size should be >= 5
    }
