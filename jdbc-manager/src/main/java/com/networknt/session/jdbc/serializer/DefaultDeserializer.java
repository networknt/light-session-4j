package com.networknt.session.jdbc.serializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;



/**
 * A default {@link Deserializer} implementation that reads an input stream
 * using Java serialization.
 */
public class DefaultDeserializer implements Deserializer<Object> {

    /**
     * Create a {@code DefaultDeserializer} with default {@link ObjectInputStream}
     * configuration, using the "latest user-defined ClassLoader".
     */
    public DefaultDeserializer() {
    }


    /**
     * Read from the supplied {@code InputStream} and deserialize the contents
     * into an object.
     * @see ObjectInputStream#readObject()
     */
    @Override
    @SuppressWarnings("resource")
    public Object deserialize(InputStream inputStream) throws IOException, SerializationFailedException {
        ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
        try {
            return objectInputStream.readObject();
        }
        catch (ClassNotFoundException ex) {
            throw new SerializationFailedException("Failed to deserialize object type", ex);
        }
    }

}
