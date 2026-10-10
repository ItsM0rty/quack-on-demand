package ai.starlake.quack.edge.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LockdownScreenSpec extends AnyFlatSpec with Matchers:

  private def denied(sql: String): Boolean = LockdownScreen.screen(sql, Set.empty).isDefined

  // Bucket-denial variant: 'lakebucket' holds DuckLake data, 'staging' does not.
  private def deniedB(sql: String): Boolean =
    LockdownScreen.screen(sql, Set("lakebucket")).isDefined

  "statement kinds" should "deny ATTACH/DETACH/INSTALL/LOAD, admit ordinary SQL" in {
    denied("ATTACH 'x.db' AS other") shouldBe true
    denied("detach other") shouldBe true
    denied("INSTALL spatial") shouldBe true
    denied("LOAD spatial") shouldBe true
    denied("SELECT 1") shouldBe false
    denied("INSERT INTO t VALUES (1)") shouldBe false
    denied("  WITH x AS (SELECT 1) SELECT * FROM x") shouldBe false
  }

  "settings" should "deny protected SET/RESET/PRAGMA, admit benign settings" in {
    denied("SET disabled_filesystems = ''") shouldBe true
    denied("set LOCK_CONFIGURATION = false") shouldBe true
    denied("RESET enable_external_access") shouldBe true
    denied("PRAGMA temp_directory = '/tmp/x'") shouldBe true
    denied("SET allowed_directories = ['/']") shouldBe true
    denied("SET schema = 'tpch1'") shouldBe false
    denied("PRAGMA table_info('t')") shouldBe false
  }

  it should "deny resource settings (memory / threads / temp size)" in {
    denied("SET memory_limit = '100GB'") shouldBe true
    denied("SET max_memory = '100GB'") shouldBe true
    denied("RESET memory_limit") shouldBe true
    denied("PRAGMA memory_limit = '100GB'") shouldBe true
    denied("SET threads = 64") shouldBe true
    denied("SET worker_threads = 64") shouldBe true
    denied("SET max_temp_directory_size = '1TB'") shouldBe true
    // resource-setting names in ordinary SQL stay admitted
    denied("SELECT memory_limit FROM settings_view") shouldBe false
    denied("SELECT * FROM t WHERE name = 'threads'") shouldBe false
  }

  "functions" should "deny local-path reads anywhere in the statement" in {
    denied("SELECT * FROM read_text('/etc/passwd')") shouldBe true
    denied("SELECT read_blob('secrets.bin')") shouldBe true
    denied("SELECT * FROM t WHERE c IN (SELECT x FROM glob('/data/*'))") shouldBe true
    denied("SELECT getenv('HOME')") shouldBe true
    denied("SELECT * FROM READ_CSV('/tmp/x.csv')") shouldBe true
  }

  it should "admit object-store URL literals, fail closed on non-literals" in {
    denied("SELECT * FROM read_parquet('s3://bucket/k.parquet')") shouldBe false
    denied("SELECT * FROM read_csv('https://x.example/data.csv')") shouldBe false
    denied("SELECT * FROM read_csv('gs://b/x.csv', header = true)") shouldBe false
    denied("SELECT * FROM read_parquet(other_col)") shouldBe true
    denied("SELECT * FROM read_parquet(concat('s3://b/', f))") shouldBe true
    denied("SELECT * FROM read_csv(['s3://b/a.csv', '/tmp/b.csv'])") shouldBe true
  }

  it should "not deny identifiers that merely contain a denied name" in {
    denied("SELECT read_text_total FROM stats") shouldBe false
    denied("SELECT * FROM my_read_csv_results") shouldBe false
  }

  "multiple occurrences" should "screen every call of a denied function" in {
    denied("SELECT * FROM read_csv('s3://b/a.csv'), read_csv('/etc/passwd')") shouldBe true
    denied("SELECT * FROM read_csv('s3://a'), read_csv('s3://b')") shouldBe false
  }

  "leading trivia" should "not defeat the first-token and settings checks" in {
    denied("/* x */ ATTACH 'y.db' AS z") shouldBe true
    denied("/* x */ SET disabled_filesystems=''") shouldBe true
    denied("-- c\nATTACH 'x' AS y") shouldBe true
    denied("\uFEFF\u00A0ATTACH 'x' AS y") shouldBe true
    denied("/* a /* nested */ b */ ATTACH 'x' AS y") shouldBe true
    denied("/* x */ SELECT 1") shouldBe false
  }

  it should "not be defeated by a leading word joiner (U+2060), a Cf character once missed by name" in {
    // U+2060 is neither `Character.isWhitespace` nor `SPACE_SEPARATOR`; confirmed separately
    // against a real DuckDB CLI that a write prefixed with it still executes. isTriviaSpace was
    // broadened from two hardcoded Cf characters (BOM, ZWSP) to the whole Cf (FORMAT) category, so
    // this deployment-lockdown gate needs the same regression witness CatalogWriteScreenSpec pins.
    denied("\u2060ATTACH 'x' AS y") shouldBe true
  }

  "quoted identifiers" should "not evade the function screen" in {
    denied("SELECT \"read_text\"('/etc/passwd')") shouldBe true
    denied("SELECT \"my_read_text\" FROM t") shouldBe false
  }

  "chained statements" should "screen each semicolon-separated statement" in {
    denied("SELECT 1; ATTACH 'x' AS y") shouldBe true
    denied("SELECT 1; SET disabled_filesystems=''") shouldBe true
    denied("SELECT 1; SELECT 2") shouldBe false
    denied("SELECT 'a;b'; SELECT 2") shouldBe false
  }

  "settings statements" should "still screen denied functions in their values" in {
    denied("SET my_var = getenv('HOME')") shouldBe true
    denied("SET enable_progress_bar = true") shouldBe false
    denied("PRAGMA table_info('t')") shouldBe false
  }

  "bare-path FROM" should "deny local replacement scans, admit remote and non-path literals" in {
    denied("SELECT * FROM '/etc/passwd.parquet'") shouldBe true
    denied("SELECT * FROM'/etc/passwd.parquet'") shouldBe true
    denied("CREATE TABLE t AS SELECT * FROM '/etc/x.parquet'") shouldBe true
    denied("SELECT * FROM 's3://b/x.parquet'") shouldBe false
    denied("SELECT * FROM t WHERE path = '/etc/passwd'") shouldBe false
    denied("SELECT * FROM customer") shouldBe false
    denied("SELECT trim(leading '/' from '/a/b.csv')") shouldBe false
  }

  it should "deny dollar-quoted and escape-string replacement scans (deep-review H4)" in {
    // DuckDB's replacement scan also accepts $$...$$ / $tag$...$tag$ and e'...' literals in
    // FROM position; the single-quote-only regex admitted them, an open local-file read on
    // lockdown pools. These forms have no legitimate non-path use in FROM position, so they
    // fail closed: denied unless provably remote.
    denied("SELECT * FROM $$/etc/passwd$$") shouldBe true
    denied("SELECT * FROM $tag$/etc/passwd$tag$") shouldBe true
    denied("SELECT * FROM e'/private/var/db/secret.csv'") shouldBe true
    // escape sequences can encode the path, so a non-remote e-string denies even when it
    // does not LOOK like a path
    denied("SELECT * FROM e'\\x2fetc\\x2fpasswd'") shouldBe true
    // the remote exemption still applies to the new forms
    denied("SELECT * FROM $$s3://b/x.parquet$$") shouldBe false
    deniedB("SELECT * FROM $$s3://lakebucket/x.parquet$$") shouldBe true
  }

  "COPY" should "deny local paths, admit remote copies with options and pure table copies" in {
    denied("COPY t FROM '/etc/x.csv'") shouldBe true
    denied("COPY t TO '/tmp/x'") shouldBe true
    denied("COPY t FROM '/etc/x.csv' (DELIMITER ',')") shouldBe true
    denied("COPY t FROM 's3://b/x.csv'") shouldBe false
    denied("COPY t TO 's3://b/x.csv' (DELIMITER '|')") shouldBe false
    denied("COPY a FROM b") shouldBe false
  }

  "read_text/read_blob" should "honor the URL exemption" in {
    denied("SELECT read_text('https://x/f.txt')") shouldBe false
    denied("SELECT read_text('/etc/passwd')") shouldBe true
  }

  "reasons" should "name the blocked construct" in {
    LockdownScreen.screen("ATTACH 'x' AS y", Set.empty).get should include("ATTACH")
    LockdownScreen
      .screen("SET lock_configuration=false", Set.empty)
      .get should include("lock_configuration")
    LockdownScreen.screen("SELECT read_text('/x')", Set.empty).get should include("read_text")
  }

  // ---- DuckLake bucket denial ----

  "bucket denial" should "deny reads on a DuckLake bucket under every object-store scheme" in {
    deniedB("SELECT * FROM read_parquet('s3://lakebucket/db-x/main/t/f.parquet')") shouldBe true
    deniedB("SELECT * FROM read_parquet('s3a://lakebucket/x.parquet')") shouldBe true
    deniedB("SELECT * FROM read_parquet('r2://lakebucket/x.parquet')") shouldBe true
    deniedB("SELECT * FROM read_csv('gs://lakebucket/x.csv')") shouldBe true
    deniedB("SELECT * FROM read_parquet('S3://LAKEBUCKET/X.PARQUET')") shouldBe true
    deniedB("SELECT \"read_parquet\"('s3://lakebucket/x.parquet')") shouldBe true
    deniedB("SELECT read_text('az://lakebucket/x.txt')") shouldBe true
    deniedB(
      "SELECT * FROM read_parquet('abfss://lakebucket@acct.dfs.core.windows.net/x.parquet')"
    ) shouldBe true
  }

  it should "not be evadable with globs above the dataPath prefix" in {
    deniedB("SELECT * FROM read_parquet('s3://lakebucket/*/main/*/*.parquet')") shouldBe true
    deniedB("SELECT * FROM read_parquet('s3://lakebucket/**/*.parquet')") shouldBe true
  }

  it should "deny a list literal containing a DuckLake-bucket element" in {
    deniedB(
      "SELECT * FROM read_parquet(['s3://staging/a.parquet', 's3://lakebucket/b.parquet'])"
    ) shouldBe true
    deniedB("SELECT * FROM read_parquet(['s3://staging/a.parquet'])") shouldBe false
  }

  it should "deny FROM-position replacement scans on a DuckLake bucket" in {
    deniedB("SELECT * FROM 's3://lakebucket/x.parquet'") shouldBe true
    deniedB("SELECT * FROM 's3://staging/x.parquet'") shouldBe false
  }

  it should "deny COPY in both directions on a DuckLake bucket" in {
    deniedB("COPY t TO 's3://lakebucket/main/t/f.parquet'") shouldBe true
    deniedB("COPY t FROM 's3://lakebucket/x.csv'") shouldBe true
    deniedB("COPY t TO 's3://staging/x.csv' (DELIMITER '|')") shouldBe false
    deniedB("COPY a FROM b") shouldBe false
  }

  it should "deny https path-style and virtual-host forms of a DuckLake bucket" in {
    deniedB(
      "SELECT * FROM read_parquet('https://minio.local:9000/lakebucket/x.parquet')"
    ) shouldBe true
    deniedB(
      "SELECT * FROM read_parquet('https://lakebucket.s3.amazonaws.com/x.parquet')"
    ) shouldBe true
    deniedB(
      "SELECT * FROM read_parquet('https://minio.local:9000/staging/x.parquet')"
    ) shouldBe false
  }

  it should "admit other buckets and everything with an empty deny-set" in {
    deniedB("SELECT * FROM read_csv('s3://staging/x.csv', header = true)") shouldBe false
    deniedB("SELECT * FROM read_parquet('gs://other/x.parquet')") shouldBe false
    denied("SELECT * FROM read_parquet('s3://lakebucket/x.parquet')") shouldBe false
    denied("COPY t TO 's3://lakebucket/x.parquet'") shouldBe false
  }

  it should "name the bucket in the denial reason" in {
    LockdownScreen
      .screen("COPY t TO 's3://lakebucket/x.parquet'", Set("lakebucket"))
      .get should include("'lakebucket'")
    LockdownScreen
      .screen("SELECT * FROM read_parquet('s3://lakebucket/x.parquet')", Set("lakebucket"))
      .get should include("'lakebucket'")
  }

  // ---- interior invisible trivia cannot hide a local-path read either ----
  //
  // Java's default `\s` (used by every regex in this file) is `[ \t\n\x0B\f\r]`. It does not
  // match NBSP (U+00A0), figure space (U+2007), narrow no-break space (U+202F), or the Cf
  // characters (zero-width space U+200B, word joiner U+2060, BOM U+FEFF). DuckDB's own parser
  // front end substitutes ASCII spaces for its whole Unicode space/format set before parsing,
  // so `SELECT * FROM<NBSP>'/etc/passwd.parquet'` is an ordinary, executable replacement scan
  // to DuckDB -- verified against a real DuckDB 1.5.4, both this and the read_parquet form
  // below return the target file's rows -- while the un-normalized regex above saw no
  // separator between `from` and the quote at all and let it straight through. Every character
  // below is written as a literal `\uXXXX` escape, never a raw invisible byte (see the
  // byte-hygiene test at the end of this file).
  "interior trivia" should "not hide a bare-path FROM behind a non-breaking space" in {
    denied("SELECT * FROM\u00A0'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a bare-path FROM behind a figure space (U+2007)" in {
    denied("SELECT * FROM\u2007'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a bare-path FROM behind a narrow no-break space (U+202F)" in {
    denied("SELECT * FROM\u202F'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a bare-path FROM behind a zero-width space (U+200B)" in {
    denied("SELECT * FROM\u200B'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a bare-path FROM behind a word joiner (U+2060)" in {
    denied("SELECT * FROM\u2060'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a bare-path FROM behind a BOM (U+FEFF)" in {
    denied("SELECT * FROM\uFEFF'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a bare-path FROM behind trivia outside every other test's character list" in {
    // I3 coverage witness, not a discriminating regression test (see the identical honesty note
    // in `StatementClassifierSpec`): U+205F (medium mathematical space) and U+2004 (three-per-em
    // space) are accepted by DuckDB as separators and appear in no other test in this file, but
    // both are ALSO `Character.isWhitespace` true (confirmed directly, not assumed), so
    // `SqlTrivia.normalize` would still convert either to an ASCII space even if its
    // `SPACE_SEPARATOR`/`FORMAT` category checks were narrowed to a hardcoded list, as long as
    // the `isWhitespace` disjunct survived the narrowing. Kept as a coverage pin;
    // `SqlTriviaSpec`'s category-derived property test is the one that actually discriminates
    // that narrowing (mutation-verified: it is the only test in the whole suite that fails when
    // `isTriviaSpace` is reverted to a named set).
    denied("SELECT * FROM\u205F'/etc/passwd.parquet'") shouldBe true
    denied("SELECT * FROM\u2004'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a denied function call behind interior trivia" in {
    denied("SELECT * FROM read_parquet\u00A0('/etc/x.parquet')") shouldBe true
    denied("SELECT * FROM read_csv\u00A0('/etc/y.csv')") shouldBe true
  }

  it should "not hide a COPY path literal behind interior trivia" in {
    denied("COPY t TO\u00A0'/tmp/out.csv'") shouldBe true
  }

  it should "still admit a remote literal with interior trivia (over-denial guard)" in {
    // The remote exemption must survive normalization: a legitimate object-store literal
    // separated from FROM by an invisible character is not local-path evidence.
    denied("SELECT * FROM\u00A0's3://bucket/x.parquet'") shouldBe false
  }

  // ---- C2: an interior comment cannot hide a local-file read either ----
  //
  // `screenOne` used to normalize trivia but never strip an INTERIOR comment. DuckDB treats a
  // comment as a SEPARATOR: verified against a real DuckDB 1.5.4, `SELECT * FROM/*x*/
  // '/tmp/probe2.csv'` and `SELECT * FROM read_csv/*x*/('/tmp/probe.csv')` both return the target
  // file's rows. Before this fix, `\s*` in every regex below did not match `/*x*/`, so
  // `barePathFrom` and `deniedFunctionIn` found no adjacency at all and admitted every one of
  // these -- the exact lockdown bypass this deployment flag is sold on closing.
  "interior comments" should "not hide a bare-path FROM behind a block comment" in {
    denied("SELECT * FROM/*x*/'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a denied function call behind a block comment" in {
    denied("SELECT * FROM read_parquet/*x*/('/etc/x.parquet')") shouldBe true
  }

  it should "not hide a COPY path literal behind a block comment" in {
    denied("COPY t TO/*x*/'/tmp/out.csv'") shouldBe true
  }

  // ---- a nested leading block comment must not put a non-keyword first ----
  //
  // Already correctly handled by this file's `stripLeading -> stripComments -> normalize` order
  // (see `screenOne`'s scaladoc) -- these pin the DENIAL outcome DuckDB's actual nesting behaviour
  // requires (verified against a real DuckDB 1.5.4: every leading example below executes and
  // writes the row / attaches), matching the sibling regression witnesses added to
  // `SqlTriviaSpec` and `StatementClassifierSpec` for the shared reader those two use instead of
  // this file's own regex.
  it should "close arbitrarily deep nesting in a leading comment before ATTACH, not just two levels" in {
    denied("/* L1 /* L2 /* L3 */ back2 */ back1 */ ATTACH 'x' AS y") shouldBe true
  }

  it should "close a nested leading comment preceded by a plain trivia character before ATTACH" in {
    denied("\u00A0/* a /* b */ c */ ATTACH 'x' AS y") shouldBe true
  }

  it should "still admit a remote literal with an interior comment (over-denial guard)" in {
    // The remote exemption must survive comment stripping exactly as it survives trivia
    // normalization: a legitimate object-store literal separated from FROM by a comment is not
    // local-path evidence.
    denied("SELECT * FROM/*x*/'s3://bucket/x.parquet'") shouldBe false
  }

  it should "leave an ordinary statement with no trivia behaving exactly as before" in {
    denied("SELECT * FROM read_parquet('s3://bucket/k.parquet')") shouldBe false
    denied("SELECT * FROM '/etc/passwd.parquet'") shouldBe true
    denied("ATTACH 'x' AS y") shouldBe true
    denied("SELECT 1") shouldBe false
  }

  // ---- C1: a comment marker inside a QUOTED region must not truncate the statement ----
  //
  // `screenOne` runs the statement through `SqlCommentStripper`, which tracked single quotes only.
  // A `/*` or `--` inside a double-quoted identifier or a dollar-quoted string therefore opened a
  // comment that never closed and swallowed the rest of the statement, so `lower` arrived as
  // `select 1 as "x` and ALL FOUR arms (deniedFunctionIn, barePathFrom, copyLocalPath,
  // settingTargeted) matched nothing. Every statement below executes on a real DuckDB 1.5.4 and
  // returns the target file's rows; a column alias is not an adversarial construct.
  "quoted comment markers" should "not hide a denied function behind a quoted identifier" in {
    denied("SELECT 1 AS \"x/*y\", * FROM read_csv('/etc/passwd.csv')") shouldBe true
  }

  it should "not hide a bare-path FROM behind a double-quoted identifier" in {
    denied("SELECT 1 AS \"x--y\", * FROM '/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a denied function behind a dollar-quoted string" in {
    denied("SELECT $$a/*b$$ AS s, * FROM read_csv('/etc/passwd.csv')") shouldBe true
    denied("SELECT $tg$a--b$tg$ AS s, * FROM read_csv('/etc/passwd.csv')") shouldBe true
  }

  it should "not hide an ATTACH behind a quoted comment marker in an earlier statement (guard)" in {
    // GUARD: mutation-tested, passes with the quoted-identifier arm reverted, because
    // `splitStatements` is already double-quote aware and hands the ATTACH over as its own
    // fragment. Pinned because it is the shape an operator would expect this file to cover.
    denied("SELECT 1 AS \"x/*y\"; ATTACH 'x' AS q") shouldBe true
  }

  it should "still admit a remote read whose alias carries a marker (over-denial guard)" in {
    denied("SELECT 1 AS \"x/*y\", * FROM read_csv('s3://bucket/x.csv')") shouldBe false
  }

  // ---- I1: a NESTED interior comment defeated every adjacency regex ----
  //
  // Same mechanism as the non-nested case pinned above, one nesting level deeper. `stripComments`
  // closed at the inner marker and left the text between the inner and the outer close sitting
  // between the keyword and its argument, so `from\s*'` and `read_csv\s*\(` found no adjacency.
  // All three execute on a real DuckDB 1.5.4 (two return the file's rows, the COPY writes it).
  "nested interior comments" should "not hide a bare-path FROM" in {
    denied("SELECT * FROM/* a /* b */ c */'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a denied function call" in {
    denied("SELECT * FROM read_csv/* a /* b */ c */('/etc/x.csv')") shouldBe true
  }

  it should "not hide a COPY path literal" in {
    denied("COPY t TO/* a /* b */ c */'/tmp/out.csv'") shouldBe true
  }

  it should "still admit a remote literal behind a nested comment (over-denial guard)" in {
    denied("SELECT * FROM/* a /* b */ c */'s3://bucket/x.parquet'") shouldBe false
  }

  // ---- m2: the ASCII space a stripped block comment leaves behind ----
  //
  // The three interior-comment tests further up pass with that space removed, because
  // `FromLiteral`, `CopyPathLiteral` and the denied-function call regex all use `\s*`. This is the
  // one lockdown rule where the space is genuinely load-bearing: `FromEscapeLiteral` uses `\s+`.
  // `SELECT * FROM/*x*/e'/tmp/probe.csv'` executes on a real DuckDB 1.5.4 and is ADMITTED when the
  // block comment is deleted rather than replaced (the regex needs at least one space between
  // `from` and `e'`), DENIED when it is replaced by one.
  "the separator a block comment leaves" should "keep FROM adjacent to an escape-string path" in {
    denied("SELECT * FROM/*x*/e'/etc/passwd.csv'") shouldBe true
  }

  it should "keep FROM adjacent to an escape-string path behind a NESTED comment too" in {
    denied("SELECT * FROM/* a /* b */ c */e'/etc/passwd.csv'") shouldBe true
  }

  // ---- m6: the lowercasing must not depend on the JVM's default locale ----
  //
  // Under `-Duser.language=tr`, `"INSTALL".toLowerCase` is a dotless i followed by `nstall`, which
  // is not the key `install` in `DeniedFirstTokens`, so INSTALL is admitted on a locked-down
  // deployment. The default locale is restored in a finally so no other test in this JVM sees it.
  //
  // The `setDefault` IS the experiment, and a test whose setup quietly stops taking effect passes
  // vacuously: under an ASCII-lowercasing default, the default and `Locale.ROOT` agree and the two
  // assertions below hold whichever fold the production code uses. So the first thing asserted is
  // that the forced default GENUINELY folds differently, and this fails loudly rather than proving
  // nothing if a JVM flag, a security manager or a future JDK stops honouring it.
  "first-token lowercasing" should "not depend on the default locale" in {
    val previous = java.util.Locale.getDefault
    try
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
      withClue("the forced Turkish default locale is not folding; this test would prove nothing") {
        "I".toLowerCase should not be "i"
        "I".toLowerCase(java.util.Locale.ROOT) shouldBe "i"
      }
      denied("INSTALL spatial") shouldBe true
      denied("INSERT INTO t VALUES (1)") shouldBe false
    finally java.util.Locale.setDefault(previous)
  }

  // ---- escape hygiene of this file's own invisible-character test literals ----
  //
  // Every trivia character exercised above must be a literal `\uXXXX` escape, never a raw
  // invisible byte pasted into the source: an editor or an "helpful" formatting pass can
  // silently decode `\u00A0` back into a raw NBSP, at which point this file still compiles and
  // every assertion above still passes (a `String` built from the escape and one built from the
  // raw byte are identical at runtime -- that's the whole point of an escape), while the next
  // reader sees what looks like ordinary blank space around a keyword with no way to tell the
  // test asserts anything about trivia at all. This test reads this very source file back and
  // fails if any codepoint above ASCII (U+007F) appears anywhere in it.
  it should "carry no raw non-ASCII codepoints in its own source file" in {
    val path = "src/test/scala/ai/starlake/quack/edge/sql/LockdownScreenSpec.scala"
    val file = new java.io.File(path)
    withClue(s"expected to find $path relative to the working directory ${file.getAbsolutePath}") {
      file.exists shouldBe true
    }
    val src = scala.io.Source.fromFile(file, "UTF-8")
    try
      val offenders = src.mkString.zipWithIndex.filter { case (c, _) => c.toInt > 0x7f }
      withClue(s"found non-ASCII codepoints at offsets ${offenders.map(_._2).mkString(", ")}: ") {
        offenders shouldBe empty
      }
    finally src.close()
  }

  // ---- the same hygiene rule for CONTROL characters, which the check above cannot see ----
  //
  // The guard above only catches a codepoint above ASCII, so it says nothing about a raw carriage
  // return (U+000D): a tool-call parameter carrying the two characters backslash-r can arrive in
  // the file as one raw CR byte, and then a test whose whole point is "DuckDB ends a line comment
  // at a bare CR" reads, to the next maintainer, as an ordinary line break inside a string
  // literal. The comparison below is written as `0x0d.toChar` deliberately: spelling it as an
  // escape would put the very byte sequence this test polices into the test itself.
  it should "carry no raw carriage return in its own source file" in {
    val path = "src/test/scala/ai/starlake/quack/edge/sql/LockdownScreenSpec.scala"
    val src  = scala.io.Source.fromFile(new java.io.File(path), "UTF-8")
    try
      val offenders = src.mkString.zipWithIndex.filter { case (c, _) => c == 0x0d.toChar }
      withClue(s"found raw CR bytes at offsets ${offenders.map(_._2).mkString(", ")}: ") {
        offenders shouldBe empty
      }
    finally src.close()
  }

  "DuckDB 2.0 statements" should "deny CONNECT / DISCONNECT and extension repository DDL" in {
    denied("CONNECT 'quack:evil.example:9494' (TOKEN 'x')") shouldBe true
    denied("  /* c */ connect 'quack:h:1'") shouldBe true
    denied("DISCONNECT") shouldBe true
    denied("CREATE EXTENSION REPOSITORY r WITH PREFIX 'https://x/'") shouldBe true
    denied("create  extension\nrepository r with prefix 'https://x/'") shouldBe true
    denied("DROP EXTENSION REPOSITORY r") shouldBe true
    denied("CREATE OR REPLACE EXTENSION REPOSITORY r WITH PREFIX 'https://x/'") shouldBe true
    denied("create or  replace\nextension repository r with prefix 'https://x/'") shouldBe true
    denied("DROP EXTENSION REPOSITORY IF EXISTS r") shouldBe true
    denied("CONNECT LOCAL") shouldBe true
    denied("SELECT 1; CONNECT 'quack:h:1'") shouldBe true
    denied("CREATE TABLE connections(a INT)") shouldBe false
    denied("SELECT * FROM connect_log") shouldBe false
  }

  it should "protect allow_extension_repositories" in {
    denied("SET allow_extension_repositories = 'allowed'") shouldBe true
    denied("RESET allow_extension_repositories") shouldBe true
  }

  "the statement splitter" should "not lose a statement behind a dollar-quoted or E-string literal" in {
    // DuckDB reads `$x$'$x$` as a dollar-quoted string holding one quote, and `E'\''` as an
    // escape string holding one quote. A splitter that knew only '...' opened a single-quoted
    // run at that inner quote and merged the ATTACH into the leading `select` fragment.
    denied("SELECT $x$'$x$; ATTACH 'x.db' AS y") shouldBe true
    denied("SELECT E'\\''; ATTACH 'x.db' AS y") shouldBe true
  }

  it should "not split on a semicolon inside a dollar-quoted string" in {
    denied("SELECT $$a; ATTACH 'x' AS y$$") shouldBe false
    LockdownScreen.splitStatements("SELECT $$a; b$$; SELECT 2") shouldBe
      List("SELECT $$a; b$$", " SELECT 2")
  }

  it should "read dollar-quote tags case-sensitively, lexing before folding case" in {
    // DuckDB 2.0 compares dollar tags case-sensitively: $A$ is not closed by $a$, so the literal
    // runs to the second $A$ and the read_text call after it runs (verified on the 2.0 CLI).
    // Folding case before lexing closed the literal at $a$ and read the call as a block comment.
    denied("SELECT $A$x$a$ /* $A$, * FROM read_text('/etc/hosts') -- */") shouldBe true
    // matching tags: the call sits in a comment for DuckDB too
    denied("SELECT $a$x$a$ /* $a$, * FROM read_text('/etc/hosts') -- */") shouldBe false
  }

  it should "split after an identifier ending in a dollar-tag shape, as DuckDB does" in {
    // DuckDB 2.0: `x$a$` is ONE identifier (identifiers continue through `$`), so the `;` after it
    // is a statement boundary and no dollar-quoted string opens there.
    LockdownScreen
      .splitStatements("SELECT 1 AS x$a$; ATTACH 'x' AS q; SELECT 2 AS y$a$")
      .length shouldBe 3
    denied("SELECT 1 AS x$a$; ATTACH 'x' AS q; SELECT 2 AS y$a$") shouldBe true
  }

  it should "lex the batch the way DuckDB 2.0 does: non-ASCII tags, backticks, the unicode pre-pass" in {
    // Each batch attaches `y` on the DuckDB 2.0 CLI.
    denied("SELECT $\u20AC$ ' $\u20AC$; ATTACH 'x.db' AS y") shouldBe true
    denied("SELECT 1 AS `a/*`; ATTACH 'x.db' AS y; SELECT 1 AS `*/`") shouldBe true
    denied(
      "SELECT 1 /* ' */ AS a\u00A0$q$; ATTACH 'x.db' AS y; SELECT 1 AS b\u00A0$q$ /* ' */"
    ) shouldBe true
  }

  // ---- function calls read from DuckDB 2.0's tokens, not a name regex ----
  //
  // DuckDB 2.0 accepts backtick-quoted identifiers, function names included: on the 2.0 CLI
  // `SELECT * FROM `read_text`('/etc/hosts')` returns the file. A call is an identifier token
  // (bare, double-quoted or backtick-quoted, possibly qualified) followed, past any trivia, by an
  // opening parenthesis token; a regex accepting only a bare or double-quoted name admitted it.

  "denied functions" should "be found under every DuckDB 2.0 identifier quoting" in {
    denied("SELECT * FROM `read_text`('/etc/hosts')") shouldBe true
    denied("SELECT * FROM `READ_TEXT`('/etc/hosts')") shouldBe true
    denied("SELECT * FROM \"read_text\"('/etc/hosts')") shouldBe true
    denied("SELECT * FROM `system`.`main`.`read_text`('/etc/hosts')") shouldBe true
    denied("SELECT * FROM system.\"main\".`read_text` /* c */ ('/etc/hosts')") shouldBe true
    denied("SELECT `getenv`('HOME')") shouldBe true
    denied("SELECT * FROM `glob`('s3://b/*')") shouldBe true
  }

  it should "not treat a backtick-quoted identifier with no parenthesis as a call" in {
    denied("SELECT `read_text` FROM t") shouldBe false
    denied("SELECT `read_text`, \"getenv\" FROM t") shouldBe false
  }

  it should "keep the remote-literal exemption for the backtick form" in {
    denied("SELECT * FROM `read_csv`('s3://bucket/x.csv')") shouldBe false
    denied("SELECT * FROM `read_csv`('/etc/x.csv')") shouldBe true
    deniedB("SELECT * FROM `read_csv`('s3://lakebucket/x.csv')") shouldBe true
  }

  it should "judge every occurrence, whatever its quoting" in {
    denied("SELECT * FROM read_csv('s3://b/x.csv'), `read_csv`('/etc/x.csv')") shouldBe true
  }

  it should "find a backtick-quoted call inside a string literal a query() call runs" in {
    // query() runs its argument as SQL; each of these reads /etc/hosts on the 2.0 CLI.
    denied("SELECT * FROM query('SELECT * FROM `read_text`(''/etc/hosts'')')") shouldBe true
    denied("SELECT * FROM query($$SELECT * FROM `read_text`('/etc/hosts')$$)") shouldBe true
    // an escape string's \x72 is `r`
    denied("SELECT * FROM query(e'SELECT * FROM \\x72ead_text(''/etc/hosts'')')") shouldBe true
  }

  "bare-path FROM" should "deny a quoted-identifier replacement scan of a local path" in {
    // DuckDB 2.0 replacement-scans a quoted identifier as it does a literal: both forms read the
    // file on the 2.0 CLI. A qualified name is joined with dots before the scan.
    denied("SELECT * FROM `/etc/x.parquet`") shouldBe true
    denied("SELECT * FROM \"/etc/x.parquet\"") shouldBe true
    denied("SELECT * FROM \"/etc/x\".\"parquet\"") shouldBe true
    denied("SELECT * FROM t JOIN `/etc/x.csv` ON true") shouldBe true
    denied("SELECT * FROM `orders`") shouldBe false
    denied("SELECT * FROM \"main\".\"orders\"") shouldBe false
    denied("SELECT * FROM `s3://b/x.parquet`") shouldBe false
    deniedB("SELECT * FROM `s3://lakebucket/x.parquet`") shouldBe true
  }

  "denied functions" should "fail closed past the string-literal nesting the scan follows" in {
    // Each literal body is scanned as SQL (query() runs one), a bounded number of levels deep.
    def nested(levels: Int): String =
      "SELECT " + (1 to levels).foldLeft("x")((body, k) => "$t" + k + "$" + body + "$t" + k + "$")
    denied(nested(3)) shouldBe false
    denied(nested(FunctionCalls.MaxDepth + 1)) shouldBe true
  }
