package com.networknt.session.jdbc;

import java.sql.SQLException;

/** Stable outcome for unavailable sessions versus JDBC storage failures. */
public final class JdbcSessionException extends IllegalStateException {
    public enum Reason { UNAVAILABLE, STORAGE_FAILURE }
    private final Reason reason;

    JdbcSessionException(Reason reason, String message, SQLException cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
