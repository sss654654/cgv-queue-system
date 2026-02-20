package com.example.seats.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * JPA AttributeConverter: List<String> <-> JSON string for MySQL JSON column.
 * e.g., ["A1", "A2", "A3"] <-> '["A1","A2","A3"]'
 */
@Converter
public class JsonListConverter implements AttributeConverter<List<String>, String> {

    private static final Logger logger = LoggerFactory.getLogger(JsonListConverter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            logger.error("List -> JSON 변환 실패: {}", attribute, e);
            return "[]";
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(dbData, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            logger.error("JSON -> List 변환 실패: {}", dbData, e);
            return Collections.emptyList();
        }
    }
}
