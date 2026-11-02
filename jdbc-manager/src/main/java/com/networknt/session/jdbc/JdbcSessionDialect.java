package com.networknt.session.jdbc;

import java.io.ByteArrayInputStream;
import java.sql.*;
import java.util.Locale;

/** Database capabilities. Select explicitly when a proxy/server reports nonstandard metadata. */
public enum JdbcSessionDialect {
    POSTGRESQL("OCTET_LENGTH(ATTRIBUTE_BYTES)", true),
    ORACLE("DBMS_LOB.GETLENGTH(ATTRIBUTE_BYTES)", false),
    SQL_SERVER("CAST(DATALENGTH(ATTRIBUTE_BYTES) AS BIGINT)", false),
    H2("OCTET_LENGTH(ATTRIBUTE_BYTES)", false),
    MYSQL("OCTET_LENGTH(ATTRIBUTE_BYTES)", false),
    MARIADB("OCTET_LENGTH(ATTRIBUTE_BYTES)", false),
    DB2("LENGTH(ATTRIBUTE_BYTES)", false),
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

    static JdbcSessionDialect detect(DatabaseMetaData metadata) throws SQLException {
        String product = normalize(metadata.getDatabaseProductName());
        String driver = normalize(metadata.getDriverName());
        // PostgreSQL driver identity survives compatible servers reporting a different product name.
        if (product.contains("postgres") || driver.contains("postgresql")) return POSTGRESQL;
        if (product.contains("oracle")) return ORACLE;
        if (product.contains("sql server")) return SQL_SERVER;
        if (product.equals("h2") || product.startsWith("h2 ")) return H2;
        if (product.contains("mariadb")) return MARIADB;
        if (product.contains("mysql")) return MYSQL;
        if (product.contains("db2")) return DB2;
        return BLOB;
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }

    void bind(PreparedStatement stmt, int index, byte[] bytes) throws SQLException {
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
        if (binaryStream) stmt.setBinaryStream(index, stream, bytes.length);
        else stmt.setBlob(index, stream, bytes.length);
    }
}
