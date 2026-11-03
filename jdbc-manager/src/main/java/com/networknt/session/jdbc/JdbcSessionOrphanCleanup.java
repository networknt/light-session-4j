package com.networknt.session.jdbc;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Explicit offline command; never invoked by the repository or application startup. */
final class JdbcSessionOrphanCleanup {
    private static final String SELECT = "SELECT A.SESSION_ID, A.ATTRIBUTE_NAME FROM light_session_attributes A "
            + "WHERE NOT EXISTS (SELECT 1 FROM light_session S WHERE S.SESSION_ID = A.SESSION_ID)";
    private static final String DELETE = "DELETE FROM light_session_attributes WHERE SESSION_ID = ? AND ATTRIBUTE_NAME = ? "
            + "AND NOT EXISTS (SELECT 1 FROM light_session S WHERE S.SESSION_ID = light_session_attributes.SESSION_ID)";
    // Give planners a direct leading-key lower bound in addition to the portable composite predicate.
    private static final String AFTER = " AND A.SESSION_ID >= ? AND (A.SESSION_ID > ? OR (A.SESSION_ID = ? AND A.ATTRIBUTE_NAME > ?))";
    private static final String ORDER = " ORDER BY A.SESSION_ID, A.ATTRIBUTE_NAME";
    record Key(String session, String attribute) { }
    record Page(Key lastKey, int selected, int removed) { }

    private JdbcSessionOrphanCleanup() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || !args[0].equals("--writers-stopped"))
            throw new IllegalArgumentException("Usage: JdbcSessionOrphanCleanup --writers-stopped [batch-size]; stop writers AND cleanup first");
        int batchSize = args.length == 2 ? Integer.parseInt(args[1]) : 128;
        if (batchSize < 1 || batchSize == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid batch size");
        String url = System.getenv("JDBC_MAINTENANCE_URL");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Set JDBC_MAINTENANCE_URL for the offline database");
        Properties credentials = new Properties();
        for (String key : List.of("user", "password")) {
            String value = System.getenv("JDBC_MAINTENANCE_" + key.toUpperCase(java.util.Locale.ROOT));
            if (value != null) credentials.setProperty(key, value);
        }
        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            connection.setAutoCommit(false);
            long total = 0;
            Key after = null;
            while (true) {
                Page page = removePage(connection, batchSize, after);
                if (page.selected() == 0) break;
                after = page.lastKey();
                total += page.removed();
                System.out.println("Orphan cleanup page: selected=" + page.selected() + ", removed=" + page.removed()
                        + ", totalRemoved=" + total);
            }
            System.out.println("Completed orphan cleanup; removed: " + total);
        }
    }

    /** Owns one transaction on a dedicated, non-autocommit maintenance connection. */
    static Page removePage(Connection connection, int batchSize, Key after) throws SQLException {
        if (batchSize < 1 || batchSize == Integer.MAX_VALUE || connection.getAutoCommit())
            throw new IllegalArgumentException("Use a positive batch size and a dedicated non-autocommit connection");
        try {
            List<Key> keys = new ArrayList<>();
            try (PreparedStatement stmt = connection.prepareStatement(SELECT + (after == null ? "" : AFTER) + ORDER)) {
                stmt.setMaxRows(batchSize);
                if (after != null) {
                    stmt.setString(1, after.session()); stmt.setString(2, after.session());
                    stmt.setString(3, after.session()); stmt.setString(4, after.attribute());
                }
                try (ResultSet rows = stmt.executeQuery()) {
                    while (rows.next()) {
                        Key key = new Key(rows.getString(1), rows.getString(2));
                        if (key.session() == null || key.attribute() == null)
                            throw new SQLException("Orphan cleanup requires non-NULL session/attribute primary keys");
                        keys.add(key);
                    }
                }
            }
            int removed = 0;
            try (PreparedStatement stmt = connection.prepareStatement(DELETE)) {
                for (Key key : keys) {
                    stmt.setString(1, key.session()); stmt.setString(2, key.attribute());
                    int count = stmt.executeUpdate();
                    if (count > 1) throw new SQLException("Orphan cleanup requires unique session/attribute primary keys");
                    removed += count;
                }
            }
            connection.commit();
            return new Page(keys.isEmpty() ? after : keys.getLast(), keys.size(), removed);
        } catch (SQLException | RuntimeException failure) {
            try { connection.rollback(); }
            catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
}
