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
    private record Key(String session, String attribute) { }

    private JdbcSessionOrphanCleanup() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || !args[0].equals("--writers-stopped"))
            throw new IllegalArgumentException("Usage: JdbcSessionOrphanCleanup --writers-stopped [batch-size]; stop writers AND cleanup first");
        int batchSize = args.length == 2 ? Integer.parseInt(args[1]) : 128;
        if (batchSize < 1 || batchSize == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid batch size");
        String url = System.getenv("JDBC_MAINTENANCE_URL");
        if (url == null) throw new IllegalArgumentException("Set JDBC_MAINTENANCE_URL for the offline database");
        Properties credentials = new Properties();
        for (String key : List.of("user", "password")) {
            String value = System.getenv("JDBC_MAINTENANCE_" + key.toUpperCase(java.util.Locale.ROOT));
            if (value != null) credentials.setProperty(key, value);
        }
        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            connection.setAutoCommit(false);
            long total = 0;
            while (true) {
                int removed = removePage(connection, batchSize);
                total += removed;
                System.out.println("Committed orphan attributes: " + total);
                if (removed < batchSize) break;
            }
        }
    }

    /** Owns one transaction on a dedicated, non-autocommit maintenance connection. */
    static int removePage(Connection connection, int batchSize) throws SQLException {
        if (batchSize < 1 || batchSize == Integer.MAX_VALUE || connection.getAutoCommit())
            throw new IllegalArgumentException("Use a positive batch size and a dedicated non-autocommit connection");
        try {
            List<Key> keys = new ArrayList<>();
            try (PreparedStatement stmt = connection.prepareStatement(SELECT)) {
                stmt.setMaxRows(batchSize);
                try (ResultSet rows = stmt.executeQuery()) {
                    while (rows.next()) keys.add(new Key(rows.getString(1), rows.getString(2)));
                }
            }
            int removed = 0;
            try (PreparedStatement stmt = connection.prepareStatement(DELETE)) {
                for (Key key : keys) {
                    stmt.setString(1, key.session()); stmt.setString(2, key.attribute());
                    removed += stmt.executeUpdate();
                }
            }
            connection.commit();
            return removed;
        } catch (SQLException | RuntimeException failure) {
            try { connection.rollback(); }
            catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
}
