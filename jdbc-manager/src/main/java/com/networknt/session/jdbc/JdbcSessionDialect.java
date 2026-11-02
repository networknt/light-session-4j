package com.networknt.session.jdbc;

import java.io.ByteArrayInputStream;
import java.sql.*;
import java.util.Locale;

/** Immutable, once-per-repository database capabilities; unknown BLOB drivers keep their original binding. */
final class JdbcSessionDialect {
    static final String AGGREGATE = "SELECT COUNT(*) AS ATTRIBUTE_COUNT, COALESCE(SUM(%s), 0) AS SESSION_BYTES FROM light_session_attributes WHERE SESSION_ID = ?";
    static final String BLOB_LENGTHS = "SELECT ATTRIBUTE_BYTES FROM light_session_attributes WHERE SESSION_ID = ?";
    final String budgetQuery;
    private final boolean binaryStream;

    private JdbcSessionDialect(String lengthExpression, boolean binaryStream) {
        this.budgetQuery = lengthExpression == null ? BLOB_LENGTHS : String.format(AGGREGATE, lengthExpression);
        this.binaryStream = binaryStream;
    }

    static JdbcSessionDialect forProduct(String product) {
        String name = product == null ? "" : product.toLowerCase(Locale.ROOT);
        if (name.equals("postgresql")) return new JdbcSessionDialect("OCTET_LENGTH(ATTRIBUTE_BYTES)", true);
        if (name.equals("oracle")) return new JdbcSessionDialect("DBMS_LOB.GETLENGTH(ATTRIBUTE_BYTES)", false);
        if (name.equals("microsoft sql server")) return new JdbcSessionDialect("CAST(DATALENGTH(ATTRIBUTE_BYTES) AS BIGINT)", false);
        if (name.equals("h2"))
            return new JdbcSessionDialect("OCTET_LENGTH(ATTRIBUTE_BYTES)", false);
        return new JdbcSessionDialect(null, false);
    }

    boolean usesBlobLengths() { return budgetQuery.equals(BLOB_LENGTHS); }

    void bind(PreparedStatement stmt, int index, byte[] bytes) throws SQLException {
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
        if (binaryStream) stmt.setBinaryStream(index, stream, bytes.length);
        else stmt.setBlob(index, stream, bytes.length);
    }
}
