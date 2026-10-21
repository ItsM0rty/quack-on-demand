package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  FederatedSource,
  FederatedSourceType,
  InitSqlScreen,
  NodeSpec,
  PoolKey,
  Role,
  RoleDistribution,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.api.{
  CreatePoolRequest,
  PoolHandlers,
  TenantDbHandlers,
  TenantDbRequest,
  UpdateTenantDbRequest
}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.federation.FederatedSourceRules
import ai.starlake.quack.ondemand.manifest.{
  ConfigManifest,
  ExportedFrom,
  ManifestImporter,
  ManifestPool,
  ManifestRoleDistribution,
  ManifestTenant,
  ManifestTenantDb
}
import ai.starlake.quack.ondemand.runtime.{LocalQuackBackend, QuackBackend}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** F01: a line starting with `.` in boot SQL is a DuckDB CLI dot-command (`.shell` / `.system` run
  * OS commands on the node host), so every write path refuses one in a tenant-authored value and
  * every backend refuses one in the resolved boot SQL of a spawn.
  */
class InitSqlDotCommandSpec extends AnyFlatSpec with Matchers:

  private val Dotted = List(
    ".shell id",
    "SET threads = 2;\n.shell id",
    "SET threads = 2;\n   .system id",
    "SET threads = 2;\r.shell id",
    "SET threads = 2;\r\n\t.read /etc/passwd",
    "SET threads = 2; .shell id",
    "﻿.shell id"
  )

  private val Clean = List(
    "",
    "SET threads = 2;",
    "SELECT 1.5 AS x;\nSELECT '.not a command';",
    "SELECT 1\n  + 0.5;",
    "INSTALL httpfs;\nLOAD httpfs;\n\n"
  )

  "InitSqlScreen" should "find a dot-command line however it is separated or indented" in {
    Dotted.foreach(sql => withClue(sql)(InitSqlScreen.dotCommandLine(sql) should not be empty))
    InitSqlScreen.dotCommandLine("SET a = 1;\nSET b = 2;\r\n  .shell id") shouldBe Some(3)
  }

  it should "admit ordinary SQL, a dot inside a line included" in {
    Clean.foreach(sql => withClue(sql)(InitSqlScreen.dotCommandLine(sql) shouldBe None))
    InitSqlScreen.dotCommandLine(null) shouldBe None
  }

  it should "name the field and line but never echo the value" in {
    val msg = InitSqlScreen.error("initSql", "SET a = 1;\n.shell cat secret-token").get
    msg should include("invalid initSql: line 2")
    msg should not include "secret-token"
  }

  private def memDb(initSql: String) =
    TenantDb("td-1", "acme", "acme_mem", TenantDbKind.InMemory, Map.empty, "", initSql = initSql)

  "TenantDb.validate" should "refuse a database initSql holding a dot-command line" in {
    TenantDb.validate(memDb("SET a = 1;\n.shell id")).get should include("invalid initSql")
    TenantDb.validateSafety(memDb(".system id")).get should include("invalid initSql")
    TenantDb.validate(memDb("SET a = 1;")) shouldBe None
  }

  private def supervisor() =
    val sup = new PoolSupervisor(
      new StubQuackBackend(),
      new NodeLoadTracker,
      new InMemoryControlPlaneStore()
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup

  "database/create" should "answer 400 for a dot-command initSql and store nothing" in {
    val sup = supervisor()
    val h   = new TenantDbHandlers(sup, requireEncryption = false)
    val out = h
      .createTenantDb(
        TenantDbRequest(tenant = "acme", name = "mem", kind = "memory", initSql = ".shell id"),
        None
      )((_: String) => None)
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)
    out.left.toOption.get._2.message should include("invalid initSql")
    sup.findTenantDb("acme", "acme_mem") shouldBe None
  }

  "database/update" should "answer 400 for a dot-command initSql and keep the stored one" in {
    val sup = supervisor()
    val h   = new TenantDbHandlers(sup, requireEncryption = false)
    h.createTenantDb(
      TenantDbRequest(tenant = "acme", name = "mem", kind = "memory", initSql = "SET a = 1;"),
      None
    )((_: String) => None)
      .unsafeRunSync() shouldBe a[Right[?, ?]]
    val out = h
      .update(
        UpdateTenantDbRequest("acme", "acme_mem", initSql = Some("SET a = 1;\n  .shell id")),
        None
      )((_: String) => None)
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)
    out.left.toOption.get._2.message should include("invalid initSql")
    sup.findTenantDb("acme", "acme_mem").map(_.initSql) shouldBe Some("SET a = 1;")
  }

  private def poolReq(initSql: Option[String]) =
    CreatePoolRequest(
      tenant = "acme",
      tenantDb = "acme_mem",
      pool = "bi",
      size = 1,
      roleDistribution = RoleDistribution(0, 0, 1),
      initSql = initSql
    )

  "pool/create" should "answer 400 for a dot-command initSql, for a tenant admin too" in {
    val sup = supervisor()
    sup.createTenantDb("acme", "mem", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    val h        = new PoolHandlers(sup, new NodeLoadTracker)
    val tenantAd =
      (_: String) => Some(SessionScope(superuser = false, manageableTenants = Set("acme")))
    val out =
      h.createPool(poolReq(Some("SET a = 1;\r.shell id")), Some("sess"))(tenantAd).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)
    out.left.toOption.map(_._2.error) shouldBe Some("invalid")
    out.left.toOption.get._2.message should include("invalid initSql")
    sup.get(PoolKey("acme", "acme_mem", "bi")) shouldBe None
  }

  "PoolSupervisor.createPool" should "refuse a dot-command initSql from any caller" in {
    val sup = supervisor()
    sup.createTenantDb("acme", "mem", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    val err = sup
      .createPool(
        PoolKey("acme", "acme_mem", "bi"),
        RoleDistribution(0, 0, 1),
        initSql = ".system id"
      )
      .attempt
      .unsafeRunSync()
    err.left.toOption.map(_.getClass) shouldBe Some(classOf[IllegalArgumentException])
    err.left.toOption.get.getMessage should include("invalid initSql")
    sup.get(PoolKey("acme", "acme_mem", "bi")) shouldBe None
  }

  private def sqlSource(setupSql: String) =
    FederatedSource(
      id = "fs-1",
      tenantDbId = "td-1",
      alias = "pg_x",
      setupSql = setupSql,
      sourceType = FederatedSourceType.Sql
    )

  "FederatedSourceRules" should "refuse a sql source whose setupSql holds a dot-command line" in {
    val errs =
      FederatedSourceRules.errors(sqlSource("ATTACH 'x' AS {{alias}};\n.shell id"), Set.empty)
    errs.exists(_.contains("invalid setupSql")) shouldBe true
    FederatedSourceRules.errors(sqlSource("ATTACH 'x' AS {{alias}};"), Set.empty) shouldBe empty
  }

  private val manifestBase = ConfigManifest(
    apiVersion = ConfigManifest.ApiVersion,
    kind = ConfigManifest.Kind,
    exportedAt = Instant.EPOCH,
    exportedFrom = ExportedFrom("0.2.0", "test")
  )

  "manifest import" should "refuse a pool initSql holding a dot-command line" in {
    val m = manifestBase.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          tenantDbs = List(ManifestTenantDb(name = "acme_mem", kind = "memory")),
          pools = List(
            ManifestPool(
              name = "bi",
              tenantDb = "acme_mem",
              roleDistribution = ManifestRoleDistribution(0, 0, 1),
              initSql = "SET a = 1;\n.shell id"
            )
          )
        )
      )
    )
    val errs = ManifestImporter.validate(m, new InMemoryControlPlaneStore()).left.toOption.get
    errs.exists(e => e.contains("pool 'bi'") && e.contains("invalid initSql")) shouldBe true
  }

  it should "refuse a tenant-db initSql holding a dot-command line and store nothing" in {
    val s = new InMemoryControlPlaneStore()
    val m = manifestBase.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          tenantDbs =
            List(ManifestTenantDb(name = "acme_mem", kind = "memory", initSql = ".shell id"))
        )
      )
    )
    val res = ManifestImporter.apply(m, s, requireEncryption = false)
    res.swap.getOrElse(Nil).mkString("\n") should include("invalid initSql")
    s.listTenantDbs("acme") shouldBe empty
  }

  private def spec(
      dbInitSql: String = "",
      extraSetupSql: String = "",
      objectStoreSql: String = "",
      lockdownSql: String = ""
  ) =
    NodeSpec(
      PoolKey("acme", "acme_mem", "bi"),
      "node-1",
      Role.Dual,
      Map.empty,
      Map.empty,
      extraSetupSql = extraSetupSql,
      dbInitSql = dbInitSql,
      lockdownSql = lockdownSql,
      objectStoreSql = objectStoreSql
    )

  // A `{{secret.NAME}}` value spliced in at spawn, or a row stored before the write-time screens,
  // reaches the backend already resolved: the backend is the last manager-side check.
  "QuackBackend.screenBootSql" should "refuse every boot SQL field of a spec" in {
    List(
      spec(dbInitSql = ".shell id"),
      spec(extraSetupSql = "CREATE SECRET s (TYPE s3, KEY_ID 'k\n.shell id');"),
      spec(objectStoreSql = "SET a = 1;\r.shell id"),
      spec(lockdownSql = "\n.system id")
    ).foreach { s =>
      val err = QuackBackend.screenBootSql(s).attempt.unsafeRunSync()
      err.left.toOption.map(_.getMessage).getOrElse("") should include(
        "refusing to start node node-1"
      )
    }
    QuackBackend
      .screenBootSql(spec(dbInitSql = "SET a = 1;"))
      .attempt
      .unsafeRunSync() shouldBe Right(())
  }

  "LocalQuackBackend.start" should "refuse a spec with dot-command boot SQL before spawning" in {
    val spawned = new AtomicBoolean(false)
    val backend = new LocalQuackBackend(
      23180,
      23181,
      commandFor = (_, _, _) =>
        spawned.set(true)
        List("true")
    )
    val err = backend.start(spec(extraSetupSql = "SET a = 1;\n.shell id")).attempt.unsafeRunSync()
    err.left.toOption.map(_.getMessage).getOrElse("") should include("invalid extraSetupSql")
    spawned.get shouldBe false
    backend.isAlive("node-1") shouldBe false
  }
