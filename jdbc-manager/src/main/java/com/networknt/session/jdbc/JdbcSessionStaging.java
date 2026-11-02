package com.networknt.session.jdbc;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.FileAttribute;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/** Extraction-local encrypted raw staging. No decoded objects or key material are persisted. */
class JdbcSessionStaging implements AutoCloseable {
    static final class Reference {
        private byte[] memory;
        private long position = -1;
        private int encryptedBytes;
        Reference(byte[] memory) { this.memory = memory; }
        Reference(long position, int encryptedBytes) { this.position = position; this.encryptedBytes = encryptedBytes; }
        long position() { return position; }
        int encryptedBytes() { return encryptedBytes; }
    }
    private final Path directory;
    private final long memoryLimit;
    private final java.util.List<Reference> references = new java.util.ArrayList<>();
    private long memoryBytes;
    private SecureRandom random;
    private Cipher encryptCipher;
    private Cipher decryptCipher;
    private FileChannel channel;
    private SecretKey key;

    JdbcSessionStaging(long memoryLimit) { this(null, memoryLimit); }
    JdbcSessionStaging(Path directory, long memoryLimit) { this.directory = directory; this.memoryLimit = memoryLimit; }

    private void open() throws IOException, GeneralSecurityException {
        if (channel != null) return;
        random = new SecureRandom();
        encryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
        decryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        key = generator.generateKey();
        Path tempDirectory = directory == null ? Path.of(System.getProperty("java.io.tmpdir")) : directory;
        var permissions = Files.getFileStore(tempDirectory).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        Path file = Files.createTempFile(tempDirectory, "light-jdbc-session-", ".tmp", permissions);
        try {
            channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
        } catch (IOException e) {
            try { Files.deleteIfExists(file); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    Reference append(byte[] bytes) throws IOException, GeneralSecurityException {
        Reference reference = new Reference(bytes);
        if (channel == null && memoryBytes + bytes.length <= memoryLimit) {
            memoryBytes += bytes.length;
        } else {
            if (channel == null) {
                open();
                for (Reference existing : references) if (existing.memory != null) writeRecord(existing);
                memoryBytes = 0;
            }
            writeRecord(reference);
        }
        references.add(reference);
        return reference;
    }

    private void writeRecord(Reference reference) throws IOException, GeneralSecurityException {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        long position = channel.size();
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        encryptCipher.updateAAD(ByteBuffer.allocate(Long.BYTES).putLong(position).array());
        byte[] encrypted = encryptCipher.doFinal(reference.memory);
        channel.position(position);
        write(ByteBuffer.wrap(nonce));
        write(ByteBuffer.wrap(encrypted));
        reference.position = position;
        reference.encryptedBytes = encrypted.length;
        reference.memory = null;
    }

    void release(Reference reference) {
        if (reference.memory != null) {
            memoryBytes -= reference.memory.length;
            reference.memory = null;
        }
    }

    byte[] read(Reference reference) throws IOException, GeneralSecurityException {
        if (reference.memory != null) return reference.memory;
        if (channel == null || reference.position() < 0) throw new IOException("JDBC staging record was released");
        channel.position(reference.position());
        byte[] nonce = new byte[12];
        read(ByteBuffer.wrap(nonce));
        byte[] encrypted = new byte[reference.encryptedBytes()];
        read(ByteBuffer.wrap(encrypted));
        decryptCipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        decryptCipher.updateAAD(ByteBuffer.allocate(Long.BYTES).putLong(reference.position()).array());
        return decryptCipher.doFinal(encrypted);
    }

    private void write(ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private void read(ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IOException("Truncated encrypted JDBC session staging record");
    }

    @Override public void close() throws IOException {
        try { if (channel != null) channel.close(); }
        finally {
            for (Reference reference : references) reference.memory = null;
            references.clear();
            memoryBytes = 0;
            key = null; encryptCipher = null; decryptCipher = null; random = null;
        }
    }
}
