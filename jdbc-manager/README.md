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
| cleanupBatchSize | 128 expired session IDs per transaction |

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
BYTEA uses binary-stream binding; other policies retain setBlob. CI contracts exercise H2/PostgreSQL; other deployment SQL and binding behavior
still require driver qualification.

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

Session liveness is derived from LAST_ACCESS_TIME (milliseconds) and
MAX_INACTIVE_INTERVAL (seconds), in both listing queries and cleanup. Cleanup uses
the exact complement of the live predicate, including the expiry boundary:

```sql
COALESCE(MAX_INACTIVE_INTERVAL, 0) < 0
OR LAST_ACCESS_TIME
   + CAST(COALESCE(MAX_INACTIVE_INTERVAL, 0) AS DECIMAL(19, 0)) * 1000 > ?
```

A negative timeout never expires. SQL NULL retains the historical getInt(NULL) = 0
behavior: it means zero timeout, not a new 1800-second default. Reads do not rewrite
MAX_INACTIVE_INTERVAL. Widening before multiplication supports large integer timeouts.

Access updates LAST_ACCESS_TIME only while the stored row is still live, using the
same predicate and the wall-clock time of the update attempt. Both the public ID-only
method and a late setLastAccessedTime cannot revive an already expired row; a future
requested access timestamp or pending timeout change does not bypass that check.
A refused refresh throws JdbcSessionException with Reason.UNAVAILABLE. The manager
handles that reason as no session and follows its existing cookie-clear path. SQL
failures use Reason.STORAGE_FAILURE with the SQLException cause, so outages are not
silently treated as logout. Refused saves use the same UNAVAILABLE reason; save SQL
failures use STORAGE_FAILURE. setLastAccessedTime applies the new
in-memory timestamp and dirty flag only after that UPDATE succeeds; existing pending
edits are preserved on refusal. save() also requires the stored row to be live, so a
stale object or later setAttribute cannot resurrect an expired session. SQL save
failures retain pending edits. Pending timeout edits affect stored liveness only when
a save of a still-live row commits. There is one UPDATE per access, with no timeout
snapshot or retry loop. If a driver reports zero changed rows for an unchanged
UPDATE, one bounded read verifies the requested live state before treating it as a
refusal. The same verification handles unchanged saves in affected-row count mode. Attribute
upserts with a zero update count check the keyed attribute's existence under the
parent lock, so repeating an identical value does not attempt a duplicate INSERT.
See [Connector/J affected-row settings](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-connection.html).

EXPIRY_TIME is a reserved legacy column. New sessions, full saves and successful
access updates explicitly write NULL, rather than a plausible but stale timestamp.
Existing cache values are ignored. Dashboards and external cleanup must derive
liveness from LAST_ACCESS_TIME/MAX_INACTIVE_INTERVAL; the column must not be used
as an expiry signal. Older binaries using it for their own listing/cleanup must be
upgraded before those readers/cleanup jobs are used. No expiry data migration is
required by the corrected repository.

Save and deletion share one transaction handler for commit, rollback/abort and
borrowed-connection restoration. Delete-by-ID removes a parent and its attributes in
one transaction. Expiry cleanup selects primary-key ID pages controlled by
light.session.jdbc.serialization.cleanupBatchSize (default 128, captured at repository
construction),
rechecks expiry on each conditional parent DELETE, and deletes attributes only for IDs
whose parent it actually deleted in that transaction. It commits each page; a failed
page rolls back while earlier committed pages remain complete. A later cleanup call
can retry remaining rows. One extra ID provides lookahead, so even an exact final
page does not require an empty probe. At most cleanupBatchSize parents are processed
per transaction. Selection cursors close before deletes, with no MARS need.

Cleanup does no attribute work for an empty page and performs only keyed child deletes
for successful parent deletes. It issues no table-wide attribute sweep or orphan
anti-join and does not delete historical/unrelated orphans. Those require separate,
explicit operator maintenance; they are not safely inferred from a routine cleanup
transaction's snapshot. Parent-first locking matches repository saves and prevents
waiting writers from inserting new attributes after deletion. Direct writers outside
that protocol remain outside the guarantee. The shipped schemas need no cascade
migration for scoped deletion.

### Before enabling corrected cleanup

Cleanup now reliably deletes expired zero-timeout and NULL-timeout sessions, together
with their attributes. NULL remains zero; it is not normalized to a 30-minute timeout.
Inventory these rows before enabling cleanup with the following plain SQL. These
counts are upper bounds if any LAST_ACCESS_TIME values are future-dated; cleanup
removes each row once that time is reached:

```sql
SELECT COUNT(*) FROM light_session
WHERE MAX_INACTIVE_INTERVAL IS NULL;
```

```sql
SELECT COUNT(*) FROM light_session
WHERE MAX_INACTIVE_INTERVAL = 0;
```

If these rows need investigation or application-specific recovery, keep cleanup
scheduled off until that work is complete. Historical attribute orphans are left
untouched by routine cleanup. The following read-only inventory can guide a separate
operator-owned maintenance task; it is never executed by the repository:

```sql
SELECT COUNT(*) FROM light_session_attributes A
WHERE NOT EXISTS (SELECT 1 FROM light_session S WHERE S.SESSION_ID = A.SESSION_ID);
```

### Explicit historical-orphan maintenance

A runnable SQL-client script ships at
[maintenance/delete_orphan_session_attributes.sql](src/main/resources/maintenance/delete_orphan_session_attributes.sql).
It has no bind parameters and is never run by startup or routine cleanup. Stop all
session writers and cleanup, take the inventory/backup required by the deployment,
then run the file in psql, SQL*Plus, sqlcmd or the deployment's SQL client. Commit
according to that client's transaction settings, confirm the orphan count is zero,
and resume writers. The maintenance window is required because this explicit global
anti-join is not safe to infer against concurrent parent inserts. It removes only
rows without parents and leaves valid session attributes intact. No operator-written
JDBC loop or new schema is required. The script is exercised in disposable H2 and
PostgreSQL fixtures; other deployment drivers remain owner-qualified.

The computed predicate may scan session metadata and cannot rely on an index on
EXPIRY_TIME. It avoids reading attribute payloads during ID selection; result limits
do not claim to bound database scan work. H2 and PostgreSQL exercise the predicate;
other deployment drivers require their own qualification.

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
