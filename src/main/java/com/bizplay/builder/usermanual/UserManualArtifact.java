package com.bizplay.builder.usermanual;

import java.time.Instant;

/** 미리보기와 내려받기에서 한 DB 스냅샷으로 읽는 사용자 매뉴얼 정상본이다. */
public record UserManualArtifact(String projectId, String systemCode, String screenId,
                                 String html, Instant createdAt,
                                 String captureBundlePath, String captureFileName,
                                 String captureLabel, Integer captureWidth,
                                 Integer captureHeight, String captureSha256) {

    public UserManualCapture capture() {
        if (captureBundlePath == null) return null;
        return new UserManualCapture(captureBundlePath, captureFileName, captureLabel,
                captureWidth, captureHeight, captureSha256);
    }
}
