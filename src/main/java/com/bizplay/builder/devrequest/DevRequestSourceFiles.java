package com.bizplay.builder.devrequest;

import java.util.List;

/**
 * 요청서의 출처(플로우 원문 등)에 달린 첨부 — 개발에 <b>목록으로만</b>전달한다.
 *
 * <p>⭐ 파일은 내려받지 않는다 — 이름 · 플로우 주소 · 크기만 싣는다 (2026-09-24 사용자 확정 · 목업 06b).
 * ⛔ 이 패키지가 SRT 를 알지 않게 하려고 둔 틈이다. 구현은 출처를 아는 쪽이 둔다.
 */
public interface DevRequestSourceFiles {

    /** @return 그 FRD(SRT 의 연결 FRD)가 출처인 첨부. 없으면 빈 목록 */
    List<SourceFile> of(String frdId);

    record SourceFile(String name, String url, Long size) { }
}
