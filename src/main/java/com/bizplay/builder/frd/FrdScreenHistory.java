package com.bizplay.builder.frd;

import java.time.Instant;
import java.util.List;

/** FRD 화면 한 장의 복원 가능한 수정 시점. */
public record FrdScreenHistory(Long id, String frdScreenId, String screenId, String screenName,
                               String html, String md, String changes, String operationId,
                               String source, Instant createdAt) {

    public List<String> changeList() {
        return changes == null ? List.of()
                : changes.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
    }
}
