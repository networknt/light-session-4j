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
  reserialization, writes or dirty flags. Rows are grouped by session ID even when
  not adjacent. If an attribute is oversized, corrupt, null or disallowed, its
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

**This remains a per-attribute policy.** There is no attribute-count limit,
per-session aggregate byte/decoded-heap budget, or listing-wide memory limit.
getSessions() still materializes its valid results. Aggregate budgets and a paginated
listing API need a separate policy/API change; this PR does not claim to solve them.
Write preflight also buffers all pending attributes, so applications must budget
session size and edit volume accordingly.

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
also run. No live external database or production session is used.

```sh
mvn -pl jdbc-manager -am test
mvn clean test
```

Identify deployed artifacts, review allowlist/filter-factory and database controls,
and rerun the partner scan before historical finding closure. A byte bound does
not make arbitrary untrusted Java deserialization safe.
