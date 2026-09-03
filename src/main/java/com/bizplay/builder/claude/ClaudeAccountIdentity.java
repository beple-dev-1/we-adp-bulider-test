package com.bizplay.builder.claude;

import java.util.Locale;

/** Claude Code가 확인한 실제 로그인 계정의 표시·중복 방지 정보다. */
public record ClaudeAccountIdentity(
        String email,
        String organizationId,
        String organizationName,
        String subscriptionType) {

    public ClaudeAccountIdentity {
        email = required(email, "Claude 계정 이메일").toLowerCase(Locale.ROOT);
        organizationId = optional(organizationId);
        organizationName = optional(organizationName);
        subscriptionType = optional(subscriptionType);
    }

    private static String required(String value, String label) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(label + "가 없습니다.");
        }
        return normalized;
    }

    private static String optional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
