package com.bizplay.builder.screendesign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 저장된 화면설계서 구조화 내용을 화면 모델로 되돌린다. */
@Component
public class ScreenDesignContentReader {

    private final ObjectMapper json;

    public ScreenDesignContentReader(ObjectMapper json) {
        this.json = json;
    }

    public ScreenDesignContent read(String value) {
        try {
            return json.readValue(value, ScreenDesignContent.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("저장된 화면설계서 내용을 읽을 수 없습니다.", unreadable);
        }
    }
}
