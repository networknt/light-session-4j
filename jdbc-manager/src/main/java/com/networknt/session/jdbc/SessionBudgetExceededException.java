package com.networknt.session.jdbc;

/** A save refused by a resource budget. Pending in-memory changes remain available for correction. */
public final class SessionBudgetExceededException extends IllegalStateException {
    public enum Reason { ATTRIBUTE_COUNT, SESSION_BYTES }
    private final Reason reason;
    private final long measured;
    private final long limit;

    SessionBudgetExceededException(Reason reason, long measured, long limit) {
        super("JDBC session budget exceeded: " + reason + " (" + measured + " > " + limit
                + "); pending changes retained");
        this.reason = reason;
        this.measured = measured;
        this.limit = limit;
    }

    public Reason getReason() { return reason; }
    public long getMeasured() { return measured; }
    public long getLimit() { return limit; }
}
