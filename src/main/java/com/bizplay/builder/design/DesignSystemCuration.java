package com.bizplay.builder.design;

import java.time.Instant;

/** 프로젝트와 시스템 하나에 저장한 디자인 시스템 큐레이션 오버레이다. */
public record DesignSystemCuration(String projectId, String systemId, String contentJson,
                                   int version, Instant updatedAt, String updatedBy) {
}
