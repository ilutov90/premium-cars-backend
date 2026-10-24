package com.premiumcars.catalog.repo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.catalog.domain.SpecGroup;

/** Converts jsonb columns to and from Java. In SQL, write them with {@code CAST(:param AS jsonb)}. */
@Component
public class JsonColumns {

    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {
    };

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private static final TypeReference<List<SpecGroup>> SPEC_SHEET = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public JsonColumns(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in database column: " + e.getMessage(), e);
        }
    }

    /** {@code [{"name": "Engine", "items": [{"name": "...", "value": "..."}]}]} */
    public List<SpecGroup> readSpecSheet(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, SPEC_SHEET);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in database column: " + e.getMessage(), e);
        }
    }

    public List<Long> readLongList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, LONG_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in database column: " + e.getMessage(), e);
        }
    }

    public List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in database column: " + e.getMessage(), e);
        }
    }

    /** @return null for a NULL column */
    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in database column: " + e.getMessage(), e);
        }
    }

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot write JSON: " + e.getMessage(), e);
        }
    }
}
