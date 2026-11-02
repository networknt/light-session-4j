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
  reserialization, writes or dirty flags. Rows are grouped by session ID in any
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
first use may detect the same immutable enum more than once. A DataSource must
still target one database family. Detection recognizes PostgreSQL driver identity
as well as product names, so compatible servers need not report exactly PostgreSQL.
MySQL and MariaDB use OCTET_LENGTH, Db2 uses LENGTH on binary columns, and the
existing PostgreSQL/H2, Oracle and SQL Server scalar policies remain available.
Each optimized check returns one count/byte-total row under the parent lock.
Empty new sessions and saves without attribute changes skip validation.

For nonstandard proxies or metadata, select a policy explicitly rather than relying
on name detection, for example:

```java
new JdbcSessionRepository(dataSource, JdbcSessionDialect.POSTGRESQL);
```

An unrecognized product/driver logs a warning before retaining the JDBC BLOB-locator
fallback (Blob.length()/Blob.free()). Locator materialization remains driver-dependent;
this fallback is not a claim of efficient validation on every driver. PostgreSQL
BYTEA uses binary-stream binding; other policies retain setBlob. Explicit BLOB
selection is available for SQL BLOB storage. SQL/driver behavior for Oracle, SQL
Server, MySQL, MariaDB and Db2 is covered by scripted contract tests, not live-driver
qualification. See the vendor [Db2 LENGTH](https://www.ibm.com/docs/en/db2/11.5.x?topic=functions-length)
and [MariaDB OCTET_LENGTH](https://mariadb.com/docs/server/reference/sql-functions/string-functions/octet_length)
definitions for binary byte-length semantics.

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
sessions. Listing budgets apply only to getSessions()/extractData(), independently of the
per-session budgets; they may deliberately be smaller. findById() uses only
per-session and per-attribute limits. Lookup caps the JDBC result at maxAttributes + 1
rows and closes its cursor immediately after rejecting the session.
The public extractData(ResultSet) helper accepts interleaved session IDs. Hydration
tracks each session independently and checks the final listing byte total only after
all rows have determined which sessions are valid. Valid prefixes from ultimately
rejected sessions do not consume the final result budget. Attribute-byte limits
still take precedence over session-byte limits on an exact tie.

To bound provisional data without ordering or a database-wide BLOB-row sort, live
staged serialized-size accounting is capped at maxListingBytes + maxSessionBytes.
One additional attribute read buffer is bounded by the remaining session/per-attribute
ceiling plus one sentinel byte. Rejection releases that session's staged accounting.
Staging excess is reported distinctly as "staging bytes"; it can refuse a stream
with too many unfinished sessions even if future rows would reject some of them.
This is a work-memory ceiling, separate from the final valid-result byte limit.
Row and distinct-session limits also include rejected sessions. These serialized
size budgets are not exact decoded-heap or driver-buffering bounds.

The production listing query has no ORDER BY and the extractor imposes no ordering
requirement. Application budgets bound consumption, not every database query plan's
upstream work; this change adds no schema migration or index requirement.

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
public extractor on deliberately interleaved rows. A separate OID fixture exercises
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
The narrow, path-filtered JDBC session contracts job runs on PRs targeting master
or 2.x only, alongside H2 and scripted JDBC tests. It has no default-branch push
trigger. Action commits and the disposable PostgreSQL image are pinned to verified
SHAs/digest in [.github/workflows/jdbc-tests.yml](../.github/workflows/jdbc-tests.yml). The PostgreSQL driver version is managed in
the parent POM. The default suite tests Oracle/SQL Server SQL selection and binding,
unknown-product fallback, settings-call counts, and rollback/restore/abort failures
through scripted JDBC objects; those tests do not qualify vendor drivers. Local
PostgreSQL qualification does not establish Oracle driver behavior, production
pool behavior, or deployment readiness.
