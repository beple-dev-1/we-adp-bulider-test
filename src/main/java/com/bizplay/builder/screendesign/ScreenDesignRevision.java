package com.bizplay.builder.screendesign;

import java.time.Instant;

/** 한 번 정상 생성된 화면설계서의 불변 개정판. */
public record ScreenDesignRevision(
        String revisionId, String projectId, String systemCode, String screenId, int revisionNo,
        String sourceFingerprint, String generatorVersion, String schemaVersion,
        String contentJson, String documentHtml, String bundlePath, String bundleManifestJson,
        Instant createdAt) { }
