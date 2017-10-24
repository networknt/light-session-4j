package com.networknt.session.jdbc.serializer;

import java.io.IOException;
import java.io.InputStream;

/**
 * A strategy interface for converting from data in an InputStream to an Object.
 */
@FunctionalInterface
public interface Deserializer<T> {

    /**
     * Read (assemble) an object of type T from the given InputStream.
     * <p>Note: Implementations should not close the given InputStream
     * (or any decorators of that InputStream) but rather leave this up
     * to the caller.
     * @param inputStream the input stream
     * @return the deserialized object
     * @throws IOException in case of errors reading from the stream
     * @throws SerializationFailedException in case of serialization failed
     */
    T deserialize(InputStream inputStream) throws IOException,  SerializationFailedException;

}