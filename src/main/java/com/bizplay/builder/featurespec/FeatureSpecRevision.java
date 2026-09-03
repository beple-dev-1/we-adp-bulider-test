package com.bizplay.builder.featurespec;

import java.time.Instant;

/** 외부 제출용 기능명세서의 불변 개정판. */
public record FeatureSpecRevision(
        String revisionId, String projectId, String systemCode, String screenId, int revisionNo,
        String sourceFingerprint, String generatorVersion, String schemaVersion,
        String contentJson, String evidenceJson, String documentHtml, Instant createdAt) { }
