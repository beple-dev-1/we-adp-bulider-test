package com.bizplay.builder.ia;

import java.time.Instant;
import java.util.List;

/**
 * 메뉴구조도 한 행. depth1~depth7 은 DB 에 각각 명시해 둔다.
 *
 * <p>⚠ <b>3마디부터는 대개 화면이 마디가 된 것이다</b> — 상세 아래 팝업이 그렇다
 * ({@link IaTreeBuilder}). 그래서 그 칸에는 메뉴 이름이 아니라 화면명이 들어온다.
 */
public record IaRow(
        String id,
        String structureId,
        int rowOrder,
        String pathKey,
        String depth1,
        String depth2,
        String depth3,
        String depth4,
        String depth5,
        String depth6,
        String depth7,
        String userType,
        String menuType,
        String screenType,
        String screenId,
        Instant updatedAt,
        String updatedBy) {

    public List<String> depths() {
        return java.util.Arrays.asList(depth1, depth2, depth3, depth4, depth5, depth6, depth7).stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    public String path() {
        return String.join(" > ", depths());
    }

    public boolean hasScreen() {
        return screenId != null && !screenId.isBlank();
    }
}
