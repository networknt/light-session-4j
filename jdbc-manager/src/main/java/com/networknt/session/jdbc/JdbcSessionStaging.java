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
final class JdbcSessionStaging implements AutoCloseable {
    record Reference(long position, int encryptedBytes) { }
    private final Path directory;
    private final SecureRandom random = new SecureRandom();
    private FileChannel channel;
    private SecretKey key;

    JdbcSessionStaging() { this(null); }
    JdbcSessionStaging(Path directory) { this.directory = directory; }

    private void open() throws IOException, GeneralSecurityException {
        if (channel != null) return;
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
        open();
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        long position = channel.size();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(ByteBuffer.allocate(Long.BYTES).putLong(position).array());
        byte[] encrypted = cipher.doFinal(bytes);
        channel.position(position);
        write(ByteBuffer.wrap(nonce));
        write(ByteBuffer.wrap(encrypted));
        return new Reference(position, encrypted.length);
    }

    byte[] read(Reference reference) throws IOException, GeneralSecurityException {
        channel.position(reference.position());
        byte[] nonce = new byte[12];
        read(ByteBuffer.wrap(nonce));
        byte[] encrypted = new byte[reference.encryptedBytes()];
        read(ByteBuffer.wrap(encrypted));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(ByteBuffer.allocate(Long.BYTES).putLong(reference.position()).array());
        return cipher.doFinal(encrypted);
    }

    private void write(ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private void read(ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IOException("Truncated encrypted JDBC session staging record");
    }

    @Override public void close() throws IOException {
        try { if (channel != null) channel.close(); }
        finally { key = null; }
    }
}
