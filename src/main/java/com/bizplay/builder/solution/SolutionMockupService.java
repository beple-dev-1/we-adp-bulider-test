package com.bizplay.builder.solution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final Logger log = LoggerFactory.getLogger(SolutionMockupService.class);

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

    /**
     * 미리보기 html 이 클론에 실제로 있나 — <b>상세 1건/요청</b>용 깊은 문.
     *
     * <p>{@code toRealPath()} 까지 재서 클론 안 심볼릭 링크가 밖을 가리키는 것까지 막는다
     * ({@code FeatureSpecService.insideCloneForReal} 과 같은 등급).
     */
    public boolean previewExists(String projectId, SolutionScreen screen, String variant) {
        try {
            Path file = previewPath(projectId, screen, variant);
            if (file == null || !Files.isRegularFile(file)) {
                return false;
            }
            Path realCore = reader.coreRoot(projectId).toAbsolutePath().normalize().toRealPath();
            return file.toRealPath().startsWith(realCore);
        } catch (IOException | RuntimeException unresolvable) {
            // ⚠ 못 잰다고 상세 화면을 깨뜨리지 않는다 — 못 재면 「없다」로 보고 빈 상태를 그린다.
            //   색인의 화면ID 가 경로로 못 쓸 글자면 여기서 500 이 나던 자리다.
            log.warn("미리보기가 있는지 못 쟀다: {}", screen.screenId(), unresolvable);
            return false;
        }
    }

    /**
     * 같은 질문을 <b>목록(최대 100건/요청)</b>용으로 얕게 잰다.
     *
     * <p>⚠ <b>{@code toRealPath()} 를 일부러 생략한다</b> — 목록 한 번에 최대 100장을 재는데
     * 매 장마다 실제 경로를 펴면 파일 계통 호출이 곱절로 난다. 그리고 이 문이 내는 것은
     * 「있음」 배지 하나뿐이라 밖의 내용이 새지 않는다 — 실제 서빙은
     * {@code SolutionPreviewController} 가 다시 전부 막는다
     * ({@code FeatureSpecService.hasDocument} 와 같은 선례).
     *
     * <p>⚠ <b>갈래 화면은 첫 기관 파일로 잰다</b>({@code variant} 를 안 넘긴다) — 목록은 기관을
     * 안 고른 자리라 고를 것이 없다. 그래서 기관마다 실물이 갈리는 화면에서는 목록의 「있음」과
     * 상세의 판정이 갈릴 수 있다. 지금 클론은 갈래 화면이 0장이라 그 자리가 안 선다.
     */
    public boolean hasPreview(String projectId, SolutionScreen screen) {
        try {
            Path file = previewPath(projectId, screen, null);
            return file != null && Files.isRegularFile(file);
        } catch (RuntimeException unreadable) {
            // ⚠ 화면 하나를 못 재는 것으로 목록 전체를 깨뜨리지 않는다.
            log.warn("미리보기가 있는지 못 쟀다: {}", screen.screenId(), unreadable);
            return false;
        }
    }

    /**
     * 클론 안 미리보기 파일 경로 — 울타리(클론 경계) 안일 때만 돌려준다.
     *
     * <p>⛔ {@code Files.exists()} 단독 호출 금지 — {@code system}·{@code screenId} 는 기획
     * 저장소 색인에서 온 검증 안 된 글자라 {@code ../} 가 섞이면 클론 밖을 가리킨다.
     */
    private Path previewPath(String projectId, SolutionScreen screen, String variant) {
        Path core = reader.coreRoot(projectId).toAbsolutePath().normalize();
        Path file = reader.fileInClone(projectId, screen.previewPath(variant))
                .toAbsolutePath().normalize();
        return file.startsWith(core) ? file : null;
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
