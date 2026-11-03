package com.networknt.session.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Load the shipped SQL-client statement for JDBC tests (clients consume the delimiter). */
final class JdbcMaintenanceTestSupport {
    private JdbcMaintenanceTestSupport() { }

    static String orphanCleanupSql() throws IOException {
        try (var stream = JdbcMaintenanceTestSupport.class.getResourceAsStream("/maintenance/delete_orphan_session_attributes.sql")) {
            if (stream == null) throw new IOException("Missing orphan-maintenance SQL");
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "").strip();
            return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        }
    }
}
