package com.networknt.session.jdbc.serializer;

import java.io.*;

/**
 * Created by gavin on 2017-09-25.
 */
public class ValueBytesConverter {


    private final int maxSerializedBytes;
    private final Deserializer<Object> deserializer;
    private final Serializer<Object> serializer;

    public ValueBytesConverter() {
        this.maxSerializedBytes = SerializedAttributeLimits.configuredMaxBytes();
        this.deserializer = new DefaultDeserializer(maxSerializedBytes);
        this.serializer = new DefaultSerializer();
    }



    public Object deserialize(byte[] source) throws SerializationFailedException {
        if (source == null) {
            throw new SerializationFailedException("Serialized session attribute must not be null");
        }
        if (source.length > maxSerializedBytes) {
            throw new SerializationFailedException("Serialized session attribute must contain at most "
                    + maxSerializedBytes + " bytes");
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
        ByteArrayOutputStream byteStream = new AttributeOutputStream(maxSerializedBytes);
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
        private final int maxSerializedBytes;

        private AttributeOutputStream(int maxSerializedBytes) {
            super(Math.min(1024, maxSerializedBytes));
            this.maxSerializedBytes = maxSerializedBytes;
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
            if (length > maxSerializedBytes - count) {
                throw new IllegalArgumentException("Serialized session attribute exceeds "
                        + maxSerializedBytes + " bytes");
            }
        }
    }

}
