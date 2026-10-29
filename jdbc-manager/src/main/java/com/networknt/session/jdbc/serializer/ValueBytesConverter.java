package com.networknt.session.jdbc.serializer;

import java.io.*;

/**
 * Created by gavin on 2017-09-25.
 */
public class ValueBytesConverter {


    private final Deserializer<Object> deserializer;
    private final Serializer<Object> serializer;

    public ValueBytesConverter() {
        this.deserializer = new DefaultDeserializer();
        this.serializer = new DefaultSerializer();
    }



    public Object deserialize(byte[] source) throws SerializationFailedException {
        if (source == null || source.length > DefaultDeserializer.MAX_SERIALIZED_BYTES) {
            throw new SerializationFailedException("Serialized session attribute must contain at most "
                    + DefaultDeserializer.MAX_SERIALIZED_BYTES + " bytes");
        }
        return deserializeStream(new ByteArrayInputStream(source));
    }

    /** Read one complete serialized attribute with a hard byte limit; caller owns the stream. */
    public Object deserializeStream(InputStream source) throws SerializationFailedException {
        try {
            return this.deserializer.deserialize(source);
        }
        catch (Throwable ex) {
            throw new SerializationFailedException("Failed to deserialize payload. " +
                    "Is the byte array a result of corresponding serialization for " +
                    this.deserializer.getClass().getSimpleName() + "?", ex);
        }
    }

    public byte[] serializer(Object source) throws SerializationFailedException{
        ByteArrayOutputStream byteStream = new AttributeOutputStream();
        try  {
            this.serializer.serialize(source, byteStream);
            return byteStream.toByteArray();
        }
        catch (Throwable ex) {
            throw new SerializationFailedException("Failed to serialize object using " +
                    this.serializer.getClass().getSimpleName(), ex);
        }
    }
    /** Bound writes as well, so new persisted values always fit the reader's byte policy. */
    private static final class AttributeOutputStream extends ByteArrayOutputStream {
        private AttributeOutputStream() {
            super(1024);
        }

        @Override
        public synchronized void write(int value) {
            checkLength(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            checkLength(length);
            super.write(bytes, offset, length);
        }

        private void checkLength(int length) {
            if (length > DefaultDeserializer.MAX_SERIALIZED_BYTES - count) {
                throw new IllegalArgumentException("Serialized session attribute exceeds "
                        + DefaultDeserializer.MAX_SERIALIZED_BYTES + " bytes");
            }
        }
    }

}
