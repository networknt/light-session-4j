package com.networknt.session.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import static org.junit.jupiter.api.Assertions.*;

class JdbcSessionStagingTest {
    @TempDir Path directory;

    @Test void encryptedRecordsRoundTripAndDetectTruncation() throws Exception {
        byte[] bytes = "sensitive test payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (JdbcSessionStaging staging = new JdbcSessionStaging(directory)) {
            assertEmptyDirectory(); // Creation is lazy for empty listings.
            var first = staging.append(bytes);
            var second = staging.append(new byte[]{1, 2, 3});
            assertArrayEquals(bytes, staging.read(first));
            assertArrayEquals(new byte[]{1, 2, 3}, staging.read(second));
            var truncated = new JdbcSessionStaging.Reference(first.position(), first.encryptedBytes() - 1);
            assertThrows(GeneralSecurityException.class, () -> staging.read(truncated));
            assertArrayEquals(bytes, staging.read(first));
        }
        assertEmptyDirectory();
    }

    @Test void temporaryFileIsRemovedWhenTheExtractionFails() throws Exception {
        assertThrows(IOException.class, () -> {
            try (JdbcSessionStaging staging = new JdbcSessionStaging(directory)) {
                staging.append(new byte[]{1, 2, 3});
                throw new IOException("synthetic extraction failure");
            }
        });
        assertEmptyDirectory();
    }

    private void assertEmptyDirectory() throws IOException {
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
