package com.bizplay.builder.businesslanguage;

import java.time.Instant;

/** 현재 표준용어 한 행과 그 행이 마지막으로 바뀐 개정 정보다. */
public record StandardTermAudit(StandardTerm value, Instant updatedAt, String updatedBy) {
}
