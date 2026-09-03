package com.bizplay.builder.frd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/** FRD 요구사항 분석에서 오간 메시지 한 줄. */
public record FrdInterviewMessage(String id, String frdId, int seq, Role role, Kind kind,
                                  String content, String questionTopic, String questionReason,
                                  String optionsJson, Instant createdAt) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Role { AI, USER }

    public enum Kind { SUMMARY, MESSAGE, QUESTION, ANSWER }

    public static FrdInterviewMessage summary(String id, String frdId, int seq, String content) {
        return new FrdInterviewMessage(id, frdId, seq, Role.AI, Kind.SUMMARY,
                content, null, null, null, null);
    }

    public static FrdInterviewMessage message(String id, String frdId, int seq, Role role, String content) {
        return new FrdInterviewMessage(id, frdId, seq, role, Kind.MESSAGE,
                content, null, null, null, null);
    }

    public static FrdInterviewMessage question(String id, String frdId, int seq, String topic,
                                                String content, String reason, List<String> options) {
        try {
            return new FrdInterviewMessage(id, frdId, seq, Role.AI, Kind.QUESTION,
                    content, topic, reason, JSON.writeValueAsString(options), null);
        } catch (JsonProcessingException impossible) {
            throw new IllegalArgumentException("질문 선택지를 저장할 수 없습니다.", impossible);
        }
    }

    public static FrdInterviewMessage answer(String id, String frdId, int seq, String content) {
        return new FrdInterviewMessage(id, frdId, seq, Role.USER, Kind.ANSWER,
                content, null, null, null, null);
    }

    public List<String> options() {
        if (optionsJson == null || optionsJson.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(optionsJson, new TypeReference<>() { });
        } catch (JsonProcessingException malformed) {
            return List.of();
        }
    }
}
