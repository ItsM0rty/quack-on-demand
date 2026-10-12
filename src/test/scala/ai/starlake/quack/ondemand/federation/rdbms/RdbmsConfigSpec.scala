package ai.starlake.quack.ondemand.federation.rdbms

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RdbmsConfigSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val ok = RdbmsConfig(
    host = "pg.example.com",
    database = "warehouse",
    user = "svc_reader",
    password = "{{secret.PG_PWD}}"
  )

  private def errs(kind: RdbmsKind, cfg: RdbmsConfig, alias: String = "sales"): List[String] =
    RdbmsConfig.validated(kind, cfg, alias, Set.empty).left.getOrElse(Nil)

  "RdbmsConfig.validated" should "accept a minimal postgres and mysql config" in {
    errs(RdbmsKind.Postgres, ok) shouldBe Nil
    errs(RdbmsKind.Mysql, ok) shouldBe Nil
  }

  it should "normalize the alias to lowercase and fill the kind's default port" in {
    val pg = RdbmsConfig.validated(RdbmsKind.Postgres, ok, "Sales_PG", Set.empty).value
    pg.alias shouldBe "sales_pg"
    pg.config.port shouldBe Some(5432)
    RdbmsConfig.validated(RdbmsKind.Mysql, ok, "m", Set.empty).value.config.port shouldBe Some(3306)
  }

  it should "validate the trimmed values, which are the ones it hands to render" in {
    val padded = ok.copy(
      host = "  pg.example.com ",
      database = " warehouse ",
      user = " svc_reader",
      password = " {{secret.PG_PWD}} ",
      schema = Some("  "),
      sslMode = Some(" require ")
    )
    val v = RdbmsConfig.validated(RdbmsKind.Postgres, padded, "sales", Set.empty).value
    v.config shouldBe ok.copy(port = Some(5432), sslMode = Some("require"))
  }

  it should "require host, database, user and password" in {
    val e = errs(RdbmsKind.Postgres, RdbmsConfig())
    e.mkString("\n") should (include("host is required") and include("database is required") and
      include("user is required") and include("password is required"))
  }

  it should "refuse a literal password" in {
    errs(RdbmsKind.Postgres, ok.copy(password = "hunter2")).mkString should
      include("must be a secret placeholder")
    errs(RdbmsKind.Mysql, ok.copy(password = "{{secret.PG_PWD}}x")).mkString should
      include("must be a secret placeholder")
  }

  it should "accept a host name, an IPv4 and an IPv6 address" in {
    for h <- List("db", "db-1.internal.example.com", "10.0.0.12", "::1", "fe80::1:2", "2001:db8::7")
    do withClue(h)(errs(RdbmsKind.Postgres, ok.copy(host = h)) shouldBe Nil)
  }

  it should "refuse a socket path and anything that is not a host" in {
    for h <- List(
        "/var/run/postgresql",
        "/tmp",
        "db host",
        "db'x",
        "-db",
        "db.",
        "host=evil",
        "{{secret.H}}",
        "db\nhost"
      )
    do
      withClue(h)(
        errs(RdbmsKind.Postgres, ok.copy(host = h)).mkString should include("host")
      )
  }

  it should "refuse an out of range port" in {
    errs(RdbmsKind.Postgres, ok.copy(port = Some(0))).mkString should include("out of range")
    errs(RdbmsKind.Postgres, ok.copy(port = Some(65536))).mkString should include("out of range")
    errs(RdbmsKind.Postgres, ok.copy(port = Some(6543))) shouldBe Nil
  }

  it should "accept schema for postgres only" in {
    errs(RdbmsKind.Postgres, ok.copy(schema = Some("sales"))) shouldBe Nil
    errs(RdbmsKind.Mysql, ok.copy(schema = Some("sales"))).mkString should
      include("schema applies only to sourceType 'postgres'")
  }

  it should "check sslMode against the kind's own values" in {
    errs(RdbmsKind.Postgres, ok.copy(sslMode = Some("verify-full"))) shouldBe Nil
    errs(RdbmsKind.Mysql, ok.copy(sslMode = Some("verify_identity"))) shouldBe Nil
    errs(RdbmsKind.Postgres, ok.copy(sslMode = Some("required"))).mkString should
      include("sslMode 'required' is not valid for sourceType 'postgres'")
    errs(RdbmsKind.Mysql, ok.copy(sslMode = Some("require"))).mkString should
      include("sslMode 'require' is not valid for sourceType 'mysql'")
  }

  it should "refuse a placeholder outside password, which would resolve a secret into the clear" in {
    errs(RdbmsKind.Postgres, ok.copy(user = "{{secret.USER}}")).mkString should
      include("user must not contain '{{'")
    errs(RdbmsKind.Postgres, ok.copy(database = "db{{x")).mkString should
      include("database must not contain '{{'")
    errs(RdbmsKind.Postgres, ok.copy(schema = Some("{{secret.S}}"))).mkString should
      include("schema must not contain '{{'")
  }

  it should "refuse control characters in literal fields" in {
    errs(RdbmsKind.Postgres, ok.copy(database = "ware\nhouse")).mkString should
      include("database must not contain control, format or invisible characters")
    errs(RdbmsKind.Postgres, ok.copy(user = "s\u0000vc")).mkString should
      include("user must not contain control, format or invisible characters")
    // Trailing ones are trimmed away before validation: the value rendered carries none.
    errs(RdbmsKind.Postgres, ok.copy(user = "svc\u0000")) shouldBe Nil
  }

  it should "accept quotes, backslashes and unicode in database, user and schema" in {
    errs(
      RdbmsKind.Postgres,
      ok.copy(database = "O'Brien \\ db", user = "us\"er", schema = Some("vente_été"))
    ) shouldBe Nil
  }

  it should "refuse a builtin or reserved alias, case-insensitively" in {
    errs(RdbmsKind.Postgres, ok, "memory").mkString should include("reserved")
    RdbmsConfig
      .validated(RdbmsKind.Postgres, ok, "Sales", Set("SALES"))
      .left
      .value
      .mkString should include("reserved")
  }

  it should "refuse an alias the Names rule rejects" in {
    errs(RdbmsKind.Postgres, ok, "1bad").mkString should include("alias")
  }

  "RdbmsConfig JSON" should "round trip and default absent fields" in {
    val cfg = ok.copy(port = Some(6432), schema = Some("s"), sslMode = Some("require"))
    RdbmsConfig.fromJson(cfg.toJson).value shouldBe cfg
    RdbmsConfig.fromJson("""{"host":"h"}""").value shouldBe RdbmsConfig(host = "h")
  }

  it should "refuse invisible, bidi and separator characters that would display unlike what attaches" in {
    for (label, ch) <- List(
        "C1 control"     -> "\u0085",
        "zero-width"     -> "​",
        "bidi override"  -> "‮",
        "bidi isolate"   -> "⁦",
        "line separator" -> " ",
        "para separator" -> " ",
        "soft hyphen"    -> "­",
        "private use"    -> "",
        "lone surrogate" -> "\ud800"
      )
    do
      withClue(label)(
        errs(RdbmsKind.Postgres, ok.copy(database = s"ware${ch}house")).mkString should
          include("database must not contain control, format or invisible characters")
      )
  }

  it should "accept supplementary characters, judged per code point" in {
    errs(RdbmsKind.Postgres, ok.copy(database = "lake_🦆")) shouldBe Nil
  }
