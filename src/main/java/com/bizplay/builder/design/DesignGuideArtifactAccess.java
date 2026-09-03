package com.bizplay.builder.design;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 디자인 가이드 iframe이 짧은 시간 동안 산출물 파일을 읽게 하는 열쇠다. */
@Component
public class DesignGuideArtifactAccess {

    private static final Duration LIFETIME = Duration.ofMinutes(15);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    public String issue(String projectId) {
        Instant now = Instant.now();
        grants.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        grants.put(ticket, new Grant(projectId, now.plus(LIFETIME)));
        return ticket;
    }

    public boolean allows(String projectId, String ticket) {
        Grant grant = grants.get(ticket);
        if (grant == null || grant.expiresAt().isBefore(Instant.now())) {
            grants.remove(ticket);
            return false;
        }
        return grant.projectId().equals(projectId);
    }

    private record Grant(String projectId, Instant expiresAt) {
    }
}
