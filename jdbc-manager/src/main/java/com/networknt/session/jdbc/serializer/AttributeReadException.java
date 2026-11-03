package com.networknt.session.jdbc.serializer;

/** Typed read rejection with byte accounting and a stable reason. */
public final class AttributeReadException extends SerializationFailedException {
    public enum Reason { ATTRIBUTE_BYTES, SESSION_BYTES, FILTER, CORRUPT_DATA }
    public final Reason reason;
    public final int bytesRead;
    public AttributeReadException(Reason reason, int bytesRead, Throwable cause) {
        super("Rejected deserialization of JDBC attribute: " + reason, cause);
        this.reason = reason;
        this.bytesRead = bytesRead;
    }
}
