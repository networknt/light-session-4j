package com.networknt.session.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.codec.SerializationCodec;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RedissonJsonConfigTest {
    @Test
    void readsExistingJsonResourcesWithoutConnectingToRedis() throws Exception {
        for (String resource : new String[]{"/singleNodeConfig.json", "/config/singleNodeConfig.json"}) {
            var original = new ObjectMapper().readTree(getClass().getResourceAsStream(resource));
            var config = RedissonJsonConfig.read(getClass().getResourceAsStream(resource));
            var expected = original.path("singleServerConfig");
            var single = config.useSingleServer();
            assertEquals(expected.path("address").asText(), single.getAddress());
            assertEquals(expected.path("database").asInt(), single.getDatabase());
            assertEquals(expected.path("timeout").asInt(), single.getTimeout());
            assertEquals(expected.path("connectionPoolSize").asInt(), single.getConnectionPoolSize());
            assertEquals(Duration.ofMillis(expected.path("retryInterval").asLong()), single.getRetryDelay().calcDelay(1));
            assertEquals(original.path("threads").asInt(), config.getThreads());
            assertEquals(original.path("nettyThreads").asInt(), config.getNettyThreads());
        }
    }

    @Test
    void retainsExplicitCodecAndDefaultPropertySubstitution() throws Exception {
        String json = """
                {"singleServerConfig":{"address":"${light.session.redisson.test.missing:-redis://localhost:6380}"},
                 "codec":{"class":"org.redisson.codec.SerializationCodec"}}
                """;
        var config = RedissonJsonConfig.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals("redis://localhost:6380", config.useSingleServer().getAddress());
        assertInstanceOf(SerializationCodec.class, config.getCodec());
    }
}
