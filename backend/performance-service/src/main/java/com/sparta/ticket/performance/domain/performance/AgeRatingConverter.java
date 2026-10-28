package com.sparta.ticket.performance.domain.performance;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** AgeRating을 DB의 code 문자열("ALL", "12" 등)로 저장하고 읽는다. */
@Converter
public class AgeRatingConverter implements AttributeConverter<AgeRating, String> {

    @Override
    public String convertToDatabaseColumn(AgeRating attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public AgeRating convertToEntityAttribute(String dbData) {
        return dbData == null ? null : AgeRating.fromCode(dbData);
    }
}
