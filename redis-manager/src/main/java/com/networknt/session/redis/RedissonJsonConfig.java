package com.networknt.session.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.client.codec.Codec;
import org.redisson.config.Config;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compatibility reader for JSON configurations supported before Redisson 4. */
final class RedissonJsonConfig {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

    private RedissonJsonConfig() {
    }

    static Config read(InputStream input) throws IOException {
        String json;
        try (input) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher matcher = VARIABLE.matcher(json);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String[] parameter = matcher.group(1).split(":-", 2);
            String value = System.getProperty(parameter[0], System.getenv(parameter[0]));
            if (value == null) {
                value = parameter.length == 2 ? parameter[1] : matcher.group();
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);

        ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addMixIn(KeyManagerFactory.class, IgnoredType.class)
                .addMixIn(TrustManagerFactory.class, IgnoredType.class)
                .addMixIn(Codec.class, CodecType.class);
        return mapper.readValue(resolved.toString(), Config.class);
    }

    @JsonIgnoreType
    private abstract static class IgnoredType {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "class")
    private abstract static class CodecType {
    }
}
