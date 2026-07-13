package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Canonical serialization and hashing shared by immutable case and dataset publication. */
final class EvalContentSupport {
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    byte[] write(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("evaluation content cannot be serialized", e);
        }
    }

    <T> T read(byte[] content, Class<T> type) {
        try {
            return mapper.readValue(content, type);
        } catch (Exception e) {
            throw new IllegalStateException("evaluation artifact cannot be read", e);
        }
    }

    String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
