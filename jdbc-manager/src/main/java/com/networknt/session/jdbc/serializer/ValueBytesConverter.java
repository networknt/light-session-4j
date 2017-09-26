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



    public Object deserialize(byte[] source) throws SerializationFailedException{
        ByteArrayInputStream byteStream = new ByteArrayInputStream(source);
        try {
            return this.deserializer.deserialize(byteStream);
        }
        catch (Throwable ex) {
            throw new SerializationFailedException("Failed to deserialize payload. " +
                    "Is the byte array a result of corresponding serialization for " +
                    this.deserializer.getClass().getSimpleName() + "?", ex);
        }
    }

    public byte[] serializer(Object source) throws SerializationFailedException{
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(1024);
        try  {
            this.serializer.serialize(source, byteStream);
            return byteStream.toByteArray();
        }
        catch (Throwable ex) {
            throw new SerializationFailedException("Failed to serialize object using " +
                    this.serializer.getClass().getSimpleName(), ex);
        }
    }
}
