# JDBC session attribute serialization limits

The default byte limit is **1,048,576 serialized bytes (1 MiB) per attribute**,
including Java serialization headers and trailing bytes. Both converter reads
and writes use the same policy. The class allowlist and depth/reference/array
filters remain in force independently of the byte limit.

This addresses Checkmarx A15 (worksheet row 38): ObjectInputFilter does not inspect
concretely encoded strings, so its former streamBytes check was not a hard byte
bound. The reader now checks the complete bounded input before ObjectInputStream
construction. See [ObjectInputStream filtering](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/ObjectInputStream.html#setObjectInputFilter(java.io.ObjectInputFilter)).

## Configuring the byte policy

For a reviewed migration, set a JVM property before constructing the repository,
converter or deserializer, for example:

```sh
-Dlight.session.jdbc.serialization.maxAttributeBytes=4194304
```

The property accepts positive integers below Integer.MAX_VALUE; zero, negatives,
empty/invalid values and overflow fail construction. There is no unlimited mode.
The extra byte used to detect oversize is reserved without integer overflow.
Each converter captures one limit for its reader and writer, so later property
changes cannot make that instance's read/write policies disagree. Existing
instances require recreation/restart to adopt a different limit. Direct
DefaultDeserializer instances also capture their limit at construction.

Raising the limit increases buffering and decoded-object memory demands. Size it
against the application heap and JDBC driver behavior; use a temporary increase
only with an approved migration plan, then restore the intended bound. Serialized
size is not a bound on decoded heap size. Allowlist extensions remain a separate
security-sensitive choice.

## Save and load behavior

- The reader consumes at most limit + 1 bytes from one complete finite attribute
  stream, rejecting oversize before deserialization hooks. Caller-supplied streams
  remain caller-owned. The repository uses getBinaryStream rather than getBytes.
- save() serializes all pending values before SQL changes. Serialization failure
  throws IllegalStateException with its cause. A failed SQL transaction attempts
  explicit rollback and throws; rollback errors are suppressed on the failure, and
  pending edits/new flags are retained for correction/retry. A failed commit may
  have an uncertain database outcome; reconcile that before retrying.
  setAttribute(), removeAttribute() and createSession() may propagate that failure.
  Failed saves leave pending in-memory edits; they do not claim those edits were
  persisted. Change flags clear only after a successful commit. A connection-close
  failure after a confirmed commit is logged without misreporting the write as failed.
- Loads hydrate the underlying MapSession directly, without setAttribute()/save(),
  reserialization, database writes or dirty flags. Rows are grouped by session ID in any
  order. If an attribute is oversized, corrupt, null or disallowed, its
  entire session is omitted for that load, including previously hydrated attributes.
  Healthy sessions remain available from getSessions(); findById() returns null for
  the rejected session. No partially hydrated session escapes and no stored row is
  automatically deleted. Query-level SQL failures retain the existing behavior.
- Column streams are closed on success/failure. A close-only IOException after a
  successful decode is logged without discarding the valid value. If decoding
  failed, a close error is suppressed on the original failure.
- The converter's output accumulator refuses oversized values before they can be
  returned for insertion. Direct DefaultSerializer usage is unchanged. Accepted
  attributes retain their Java serialization format.

## Upgrade and remaining resource boundaries

Previously stored oversized attributes may be omitted with their session. Inventory
sizes without printing values, then invalidate/regenerate those sessions, move
large application data out of session attributes, or use a reviewed temporary
limit increase. Applications must handle unavailable sessions through their normal
session-invalid/missing behavior. Preserve least-privilege database writes.

Serialized-byte and count budgets now cover sessions and listings as described below.
Decoded heap is still not bounded precisely; getSessions() materializes its bounded results.

The application bounds stream consumption, but a JDBC driver may buffer a column
internally. Review production drivers and database-side size constraints, such as
an appropriate PostgreSQL octet_length(attribute_bytes) check after inspecting
existing rows. No schema migration/live database change is applied here, and
PostgreSQL/Oracle production-driver buffering is not qualified by H2 tests.

## Validation

The suite covers root/nested strings, string arrays, exact boundaries/trailing
bytes, configurable policies, retained filters, hooks blocked before execution,
stream ownership/cleanup, and null/corrupt/I/O failures. Public H2 repository tests
exercise oversized setAttribute(), failed createSession(), explicit rollback after
partial SQL work, correction/retry, mixed valid/rejected sessions and read-only
hydration (including a custom writeObject counter). Existing H2 HTTP-session tests
also run. The default suite uses H2 only; the opt-in PostgreSQL contract below uses
a caller-supplied disposable database, never production sessions.

```sh
mvn -pl jdbc-manager -am test
mvn clean test
```

Identify deployed artifacts, review allowlist/filter-factory and database controls,
and rerun the partner scan before historical finding closure. A byte bound does
not make arbitrary untrusted Java deserialization safe.

## Aggregate resource budgets (A15 follow-up)

[Issue #70](https://github.com/networknt/light-session-4j/issues/70) adds positive
integer system properties captured when a repository is constructed:

| Property prefix `light.session.jdbc.serialization.` | Default |
| --- | ---: |
| maxAttributes | 64 attributes per session |
| maxSessionBytes | 4194304 serialized bytes per session |
| maxListingBytes | 67108864 serialized bytes per result |
| maxListingSessions | 1000 distinct session IDs, including rejected sessions |
| maxListingRows | 65000 JDBC result rows |

Existing per-attribute byte and object-graph/class restrictions still apply.
Save serializes only pending values before acquiring a connection. Obvious pending
count/byte excess fails before SQL. The transaction writes the parent session row
first (holding its database write lock), applies the delta, and validates the actual
stored attribute count and serialized lengths before commit. An over-budget result rolls
back with pending edits retained. Concurrent repository writers therefore cannot
jointly commit an over-budget session. All writers must follow this parent-row
locking protocol; direct external attribute writes are outside this guarantee.
Unchanged objects are neither reserialized nor rewritten. Database capabilities are
cached lazily without holding a repository monitor over metadata I/O; concurrent
first use may detect the same immutable internal policy more than once. Automatic
selection uses canonical server identities, known aliases, and the structured DB2/
platform family (including PPC64LE, Z64 and SUN64); AS/400 has an explicit alias.
Arbitrary substrings and wire-protocol driver names do not select server SQL. A DataSource must target one
database family. Unknown identities retain the warned BLOB-locator fallback.

For configuration-driven deployments with nonstandard server metadata, set a JVM
property before constructing the repository:

```sh
-Dlight.session.jdbc.databaseDialect=postgresql
```

Values are auto (the default), postgresql, oracle, sql-server, h2, mysql, mariadb,
db2, and blob. Invalid values fail construction. This setting is captured once,
like the byte policies; no public dialect enum or additional public constructor is
introduced. The existing JdbcSessionRepository(DataSource) constructor is unchanged.
A PostgreSQL protocol driver alone cannot establish a server's SQL capabilities.

MySQL/MariaDB and PostgreSQL/H2 use OCTET_LENGTH, Oracle uses DBMS_LOB.GETLENGTH,
and SQL Server uses CAST(DATALENGTH(...) AS BIGINT). Db2 widens each LENGTH to
BIGINT *before* SUM to prevent INTEGER aggregate overflow. Each optimized check
returns one count/byte-total row under the parent lock. Empty new sessions and saves
without attribute changes skip validation. The blob override selects Blob.length()/
Blob.free() locator validation; materialization remains driver-dependent. PostgreSQL
BYTEA uses binary-stream binding; other policies retain setBlob. Non-H2/PostgreSQL
SQL and binding behavior has scripted coverage only, not live-driver qualification.

Only saves that need stored validation inspect isolation and temporarily select
READ_COMMITTED if necessary. The original isolation is restored only when changed.
Autocommit is inspected per borrowed connection (it cannot safely be cached across
a pool), and changed/restored only when originally enabled. After commit/rollback,
restoration failures are suppressed on the original save failure, or logged after
a confirmed commit. Failed rollback or restoration aborts the connection; an
unresolved transaction never has settings changed that might implicitly commit it.
Session count/byte write rejections are SessionBudgetExceededException (an
IllegalStateException) with typed reason, measured size and limit; pending edits
remain available for correction.

Read hydration rejects the entire over-budget session while retaining healthy
sessions. Listing budgets apply to getSessions() and the deprecated extractData() helper, independently of the
per-session budgets; they may deliberately be smaller. findById() uses only
per-session and per-attribute limits. Lookup caps the JDBC result at maxAttributes + 1
rows and closes its cursor immediately after rejecting the session.
getSessions() does not stage payloads or use temporary storage. It reads a bounded
list of live IDs, closes that cursor, then reads and validates each complete session
through the primary-key lookup. This takes one ID query plus up to maxListingSessions
session queries using one prepared statement with rebound parameters; it avoids a global BLOB-row sort and MARS requirements. Row work is
counted across the session reads, and only fully valid sessions consume the listing
byte budget. Payload retention is the accepted result plus one bounded candidate.
Deleted sessions and sessions that expire before their payload query are omitted.
Refreshes and attribute updates completed before that query are read from the database;
newly live sessions absent from the initial ID selection appear on the next listing.
The API does not promise a transactionally consistent cross-session snapshot. Query failures discard
partial results; cleanup-only cursor/connection failures are logged after successful
reads and do not discard valid data.

The public extractData(ResultSet) compatibility helper is deprecated. It preserves
arbitrary interleaved IDs and enforces row, distinct-session and retained-byte caps
while streaming. The byte cap includes incomplete candidates, and rejected IDs still
count toward the distinct-session ceiling. Rejecting a candidate releases its retained
bytes; if earlier rows already exceed maxListingBytes, extraction fails immediately,
even if later rows would reject those candidates. This is a deliberate fail-fast
retention policy for caller-owned unordered cursors. Use getSessions() when only
complete valid sessions should consume the listing byte budget.
The helper retains at most maxListingBytes of serialized-equivalent candidates plus
one bounded attribute being hydrated, not maxListingSessions × maxSessionBytes.
Decoded JVM heap and driver/serializer buffers remain distinct from serialized sizes.
Neither path uses temporary files, spill storage, or encryption machinery.

Expiry timestamps use milliseconds: last-access time plus timeout seconds × 1000.
A negative timeout stores Long.MAX_VALUE (never expires). A SQL NULL timeout means
the MapSession default of 1800 seconds, independently of the configured timeout for
new sessions. Hydration and both access entry points use this same rule.
Loaded sessions retain their last persisted timeout separately from pending edits.
Access computes expiry in Java and conditionally updates last-access/expiry only if
the stored timeout still matches that snapshot (including an explicit IS NULL branch).
An unsaved timeout edit does not affect stored expiry. A concurrent save causes the
access update to reread the timeout and retry, up to three attempts; continued churn
logs an SQL failure instead of writing inconsistent expiry. The public ID-only method
reads the stored timeout first and uses the same guarded update. Normal loaded access
requires one UPDATE, no SELECT or SQL arithmetic; retries read only timeout metadata.

### Upgrade existing expiry timestamps

Stop **all session writers and expiry cleanup** before migration. Mixed-version
writes are unsupported: an old node can re-corrupt expiry after any successful batch.
The migration repairs existing rows; it cannot fence old binaries. Do not enable
listing/cleanup until old nodes are stopped and the final verification sweep completes.
Resume only corrected-version writers after that sweep; do not restart old binaries.

Use the packaged [migration SQL](src/main/resources/migrations/fix_session_expiry_milliseconds.sql)
as a PreparedStatement with two VARCHAR parameters: the first and last session IDs
in a bounded batch, inclusive. There is deliberately no trailing semicolon for JDBC,
including Oracle. It normalizes NULL timeouts to 1800 seconds, repairs expiry from
last-access/timeout, and skips rows already correct:

```sql
UPDATE light_session
SET MAX_INACTIVE_INTERVAL = COALESCE(MAX_INACTIVE_INTERVAL, 1800),
    EXPIRY_TIME = CASE
    WHEN COALESCE(MAX_INACTIVE_INTERVAL, 1800) < 0 THEN 9223372036854775807
    ELSE LAST_ACCESS_TIME + CAST(COALESCE(MAX_INACTIVE_INTERVAL, 1800) AS DECIMAL(19, 0)) * 1000
END
WHERE SESSION_ID >= ? AND SESSION_ID <= ?
  AND (MAX_INACTIVE_INTERVAL IS NULL OR EXPIRY_TIME IS NULL OR EXPIRY_TIME <> CASE
    WHEN COALESCE(MAX_INACTIVE_INTERVAL, 1800) < 0 THEN 9223372036854775807
    ELSE LAST_ACCESS_TIME + CAST(COALESCE(MAX_INACTIVE_INTERVAL, 1800) AS DECIMAL(19, 0)) * 1000
END)
```

Choose a batch size suitable for the deployment (for example, 1000 IDs). Read IDs
using `SELECT SESSION_ID FROM light_session ORDER BY SESSION_ID` with JDBC
setMaxRows(batchSize). For subsequent batches use
`SELECT SESSION_ID FROM light_session WHERE SESSION_ID > ? ORDER BY SESSION_ID`,
binding the last committed ID and using the same row cap. Bind each batch's first/last
returned IDs to the UPDATE. Commit each batch, then record its upper ID checkpoint.
On restart, resume after that checkpoint; if commit status is uncertain, rerun that
same inclusive range. The mismatch predicate makes retries idempotent. No batch
updates IDs outside its bounds, and every batch contains at most the selected number
of IDs while writers remain stopped.

After completing the batches, rescan from the beginning and rerun bounded ranges;
a clean verification sweep updates zero rows. If expiry is changed again, keep
cleanup disabled, stop the remaining old writer, and repeat the sweep. No automatic
startup migration runs. The migration and restart/re-corruption regressions run on
H2 and PostgreSQL. Validate the statement with the deployment's schema/driver before
upgrading other database products; Oracle, SQL Server and Db2 are not live-qualified.

A listing that exceeds a listing budget throws IllegalStateException;
it does not masquerade as an empty or partial successful result. Stream consumption
is bounded before ObjectInputStream is constructed. Inventory existing sessions
before upgrading; a previously accepted large session can now be unavailable.
Raise limits only after reviewing the application's heap and database budgets.

These are serialized-byte/count budgets, not an exact bound on decoded object
heap, JVM/provider copies or a JDBC driver's internal buffering. getSessions still
materializes its now-bounded result; use narrower application queries for larger
inventories. No production-driver or live deployment qualification is implied.

### Disposable PostgreSQL contract

The PostgreSQL contract exercises concurrent inserts, growing UPDATEs, removal-plus-add
deltas, BYTEA streamed lookup, lookup row limits, healthy/rejected listings, and the
public extractor on deliberately interleaved rows. An additional regression verifies
that native listing tolerates large rejected prefixes while the deprecated helper
fails at its retained-byte ceiling. A separate OID fixture exercises
real large-object locator budget validation (not general PostgreSQL OID session-read
support). Test-owned large objects are unlinked before dropping that fixture schema.
Connections return to SERIALIZABLE and autocommit after commits and budget rollbacks.
The tests create and drop uniquely named schemas only.
Supply an explicitly disposable database URL (credentials below are synthetic):

```sh
mvn -pl jdbc-manager -am -Pjdbc-postgres-tests \
  '-DjdbcPostgresUrl=jdbc:postgresql://127.0.0.1:PORT/budget?user=budget&password=budget' \
  -Dtest=JdbcSessionPostgresBudgetTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The normal local suite skips this test and does not open PostgreSQL connections.
The JDBC session contracts job runs on every PR targeting master or 2.x, and after
pushes/merges to those branches. It has no path filters that could skip a required
check. setup-java uses the verified v6.1.0 release; action commits and the disposable
PostgreSQL image are pinned to SHAs/digest in [.github/workflows/jdbc-tests.yml](../.github/workflows/jdbc-tests.yml). The PostgreSQL driver version is managed in
the parent POM. The default suite tests Oracle/SQL Server SQL selection and binding,
unknown-product fallback, settings-call counts, and rollback/restore/abort failures
through scripted JDBC objects; those tests do not qualify vendor drivers. Local
PostgreSQL qualification does not establish Oracle driver behavior, production
pool behavior, or deployment readiness.
