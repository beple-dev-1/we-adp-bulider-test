package com.bizplay.builder.solution;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ③ 솔루션 목업 — 레포에서 읽은 화면과 빌더 DB 의 「실물과 다름」 표시를 한자리에 모은다.
 *
 * <p>⚠ <b>둘의 성격이 다르다.</b> 화면은 기획 저장소가 정본이라 읽기만 하고, 표시는 빌더가
 * 정본이라 쓴다. 한 화면에 나란히 뜨지만 <b>고칠 수 있는 것은 표시뿐</b>이다.
 */
@Service
public class SolutionMockupService {

    private final SolutionScreenReader reader;
    private final MockupMismatchMapper mismatches;

    public SolutionMockupService(SolutionScreenReader reader, MockupMismatchMapper mismatches) {
        this.reader = reader;
        this.mismatches = mismatches;
    }

    public List<SolutionScreen> screens(String projectId) {
        return reader.read(projectId);
    }

    public Optional<SolutionScreen> screen(String projectId, String screenId) {
        return reader.read(projectId).stream()
                .filter(screen -> screen.screenId().equals(screenId))
                .findFirst();
    }

    /** 화면ID 마다 몇 건 짚혔나. 목록이 배지를 다는 데 쓴다. */
    public Map<String, Long> mismatchCounts(String projectId) {
        return mismatches.selectByProjectId(projectId).stream()
                .collect(Collectors.groupingBy(MockupMismatch::screenId, Collectors.counting()));
    }

    /** 한 화면에 짚힌 것들. <b>새것이 앞</b>이다. */
    public List<MockupMismatch> mismatchesOf(String projectId, String screenId) {
        return mismatches.selectByProjectId(projectId).stream()
                .filter(found -> found.screenId().equals(screenId))
                .toList();
    }

    /**
     * 「실물과 다름」을 짚어 둔다.
     *
     * <p>⛔ <b>보정 권한도 Claude 자격도 요구하지 않는다</b> (2026-08-14 설계). 발견하는 자리와
     * 고치는 자리가 다르고, 발견은 BRD 작업 중에 손 안 떼고 찍고 지나가는 것이다.
     *
     * <p>⚠ 사유가 비면 <b>아무것도 안 남긴다.</b> DB 의 CHECK 도 같은 것을 막지만 그때는 500 이
     * 되고, 사람이 고칠 수 있는 것에 500 을 내면 안 된다.
     *
     * @throws IllegalArgumentException 사유가 비었을 때
     */
    @Transactional
    public void report(String projectId, String screenId, String reason, String reporterId) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("어디가 다른지 한 줄 적어야 표시할 수 있습니다.");
        }
        mismatches.insert(projectId, screenId, trimmed, reporterId);
    }
}
