package com.premiumcars.catalog.repo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Converts jsonb columns to and from Java. In SQL, write them with {@code CAST(:param AS jsonb)}. */
@Component
public class JsonColumns {

    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {
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
