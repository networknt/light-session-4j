# JDBC session attribute serialization limits

The default JDBC converter accepts at most **1,048,576 serialized bytes (1 MiB)**
per attribute, including Java serialization headers and any trailing bytes. The
same fixed limit applies to new converter writes. Values at the exact byte limit
are permitted, provided the class allowlist and graph limits also permit them.
No property enables larger values.

This closes the byte-limit gap in Checkmarx A15 (worksheet row 38): Java's
ObjectInputFilter is not called for concrete strings, so checking streamBytes
inside that filter did not prevent oversized strings/string arrays from loading.
See [ObjectInputStream filtering](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/ObjectInputStream.html#setObjectInputFilter(java.io.ObjectInputFilter)).

## Read and write behavior

- DefaultDeserializer reads at most limit + 1 bytes from one complete attribute
  stream and rejects oversize before constructing ObjectInputStream. The existing
  class allowlist and depth/reference/array checks still run for accepted inputs.
- ValueBytesConverter.deserialize(byte[]) rejects oversized arrays before copying
  or deserializing. The new deserializeStream method provides the same bounded
  behavior without changing the existing byte-array method's signature.
- JdbcSessionRepository uses ResultSet.getBinaryStream rather than getBytes and
  closes the column stream on success or failure. Null/corrupt/oversized columns
  fail through the checked SQL-exception path. Caller-supplied streams passed
  directly to the converter/deserializer remain the caller's responsibility.
- Converter serialization stops before its output accumulator exceeds the limit;
  it cannot return an oversized value for insertion as an attribute. Oversize
  produces SerializationFailedException. Existing repository error/transaction
  handling is retained; this is not a new atomic-write guarantee.

The input API now expects one complete finite serialized-attribute stream. It
consumes through EOF (up to limit + 1), rather than leaving trailing stream data
for another operation. The Java serialization format is unchanged for accepted
attributes. Direct DefaultSerializer usage outside the JDBC converter is unchanged.

## Upgrade and database boundaries

Previously persisted oversized attributes no longer load. Inventory their sizes
without printing values, then invalidate/regenerate those sessions or migrate
large application data out of session attributes before rollout. The existing
repository catches SQL read failure and returns no loaded session; applications
must handle that as their normal unavailable/invalid-session case. Enlarging the
class/package allowlists does not disable this byte cap and remains a separate
security-sensitive choice.

The application stops consuming the JDBC stream after limit + 1 bytes. A JDBC
driver may still buffer a column internally: this does not promise a driver-wide
heap bound or server-side truncation. Review production drivers and consider an
appropriate database-side attribute-size constraint. For PostgreSQL BYTEA, for
example, review an octet_length(attribute_bytes) <= 1048576 constraint after
checking existing rows. No schema migration or live database changes are applied
by this PR. PostgreSQL/Oracle production-driver behavior was not qualified here.

## Validation

The regression tests exercise root/nested large strings, string arrays, exact
boundaries, trailing bytes, write rejection, hooks blocked before execution,
retained graph filters, caller stream ownership, bounded column consumption and
JDBC stream cleanup/null/I/O failures. Existing H2-backed JDBC tests remain part
of the suite. ResultSet stream probes are synthetic and do not connect to a live
external database.

```sh
mvn -pl jdbc-manager -am test
mvn clean test
```

This byte bound is an additional safeguard, not permission to deserialize arbitrary
untrusted classes. Keep database writes least-privileged, review effective
allowlist/filter-factory settings, identify the deployed artifact and rerun the
partner scan before closing the historical finding.
