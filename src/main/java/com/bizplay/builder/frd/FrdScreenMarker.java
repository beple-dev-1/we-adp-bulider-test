package com.bizplay.builder.frd;

import java.time.Instant;

/** FRD 화면의 DOM 요소에 연결해 둔 실행 마커 한 건. */
public record FrdScreenMarker(String id, String frdScreenId, Integer markerNo,
                              String authorAccountId, String authorName,
                              String selector, String elementLabel,
                              Double relativeX, Double relativeY,
                              Double documentX, Double documentY,
                              String description, Instant createdAt, Instant updatedAt) {
}
