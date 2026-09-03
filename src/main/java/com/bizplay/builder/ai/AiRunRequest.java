package com.bizplay.builder.ai;

import java.nio.file.Path;
import java.util.List;

/**
 * 실행 하나를 시작하는 데 드는 재료 전부.
 *
 * <p>⚠ <b>{@code workDir}·{@code filesToRestore} 가 없으면 러너도 스냅샷도 못 부른다.</b>
 * 재료가 더 필요해지면 <b>인자를 늘리지 말고 이 record 를 넓혀라.</b>
 *
 * @param work           잠기는 단위. ⛔ 산출물 하나가 아니라 <b>일</b>이다
 * @param filesToRestore 실행 전에 찍어 두고 실패하면 통째로 되돌릴 파일들.
 *                       ⚠ <b>되돌리기는 계획 2 Task 4 가 붙인다</b> — 이 회차는 받아만 두고 안 쓴다
 */
public record AiRunRequest(
        WorkKey work,
        AiRunKind kind,
        String projectId,
        String accountId,
        Path workDir,
        List<Path> filesToRestore,
        String instruction) {

    public AiRunRequest {
        if (work == null || kind == null) {
            throw new IllegalArgumentException("일 열쇠와 갈래는 있어야 한다");
        }
        if (workDir == null) {
            throw new IllegalArgumentException("파일을 만질 자리가 없다");
        }
        // ⛔ 열쇠의 프로젝트와 실행의 프로젝트가 어긋나면 유일 인덱스가 엉뚱한 짝을 잰다.
        if (!work.projectId().equals(projectId)) {
            throw new IllegalArgumentException("일 열쇠의 프로젝트와 실행의 프로젝트가 다르다");
        }
        filesToRestore = filesToRestore == null ? List.of() : List.copyOf(filesToRestore);
    }
}
