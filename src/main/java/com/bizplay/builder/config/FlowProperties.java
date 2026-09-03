package com.bizplay.builder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Flow 사용자 API 연결 설정. API 키가 없으면 게시물 가져오기만 닫힌다. */
@ConfigurationProperties(prefix = "builder.flow")
public record FlowProperties(String apiKey, String baseUrl, Duration timeout) {

    public FlowProperties {
        apiKey = blankToNull(apiKey);
        baseUrl = blankToNull(baseUrl) == null
                ? "https://api.flow.team"
                : baseUrl.strip().replaceAll("/+$", "");
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
    }

    public boolean configured() {
        return apiKey != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
