package com.networknt.session.jdbc;

import java.io.ByteArrayInputStream;
import java.sql.*;
import java.util.Locale;

/** Internal database capabilities; configuration overrides are captured at repository construction. */
enum JdbcSessionDialect {
    POSTGRESQL("OCTET_LENGTH(ATTRIBUTE_BYTES)", true),
    ORACLE("DBMS_LOB.GETLENGTH(ATTRIBUTE_BYTES)", false),
    SQL_SERVER("CAST(DATALENGTH(ATTRIBUTE_BYTES) AS BIGINT)", false),
    H2("OCTET_LENGTH(ATTRIBUTE_BYTES)", false),
    MYSQL("OCTET_LENGTH(ATTRIBUTE_BYTES)", false),
    MARIADB("OCTET_LENGTH(ATTRIBUTE_BYTES)", false),
    DB2("CAST(LENGTH(ATTRIBUTE_BYTES) AS BIGINT)", false),
    BLOB(null, false);

    static final String BLOB_LENGTHS = "SELECT ATTRIBUTE_BYTES FROM light_session_attributes WHERE SESSION_ID = ?";
    final String budgetQuery;
    final boolean blobLengths;
    private final boolean binaryStream;

    JdbcSessionDialect(String lengthExpression, boolean binaryStream) {
        this.blobLengths = lengthExpression == null;
        this.budgetQuery = blobLengths ? BLOB_LENGTHS
                : "SELECT COUNT(*) AS ATTRIBUTE_COUNT, COALESCE(SUM(" + lengthExpression
                + "), 0) AS SESSION_BYTES FROM light_session_attributes WHERE SESSION_ID = ?";
        this.binaryStream = binaryStream;
    }

    static final String PROPERTY = "light.session.jdbc.databaseDialect";

    static JdbcSessionDialect configured() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.trim().equalsIgnoreCase("auto")) return null;
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(PROPERTY + " must be auto, postgresql, oracle, sql-server, h2, mysql, mariadb, db2 or blob");
        }
    }

    static JdbcSessionDialect detect(DatabaseMetaData metadata) throws SQLException {
        String product = metadata.getDatabaseProductName();
        String name = product == null ? "" : product.trim().toLowerCase(Locale.ROOT);
        // Server identities select SQL; a wire-protocol driver does not identify the server.
        return switch (name) {
            case "postgresql" -> POSTGRESQL;
            case "oracle", "oracle database" -> ORACLE;
            case "microsoft sql server", "sql server" -> SQL_SERVER;
            case "h2" -> H2;
            case "mysql" -> MYSQL;
            case "mariadb" -> MARIADB;
            case "db2", "db2/linuxx8664", "db2/aix64", "db2/nt64" -> DB2;
            default -> BLOB;
        };
    }

    void bind(PreparedStatement stmt, int index, byte[] bytes) throws SQLException {
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
        if (binaryStream) stmt.setBinaryStream(index, stream, bytes.length);
        else stmt.setBlob(index, stream, bytes.length);
    }
}
