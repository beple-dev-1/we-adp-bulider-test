package com.bizplay.builder.featurespec;

import com.bizplay.builder.solution.SolutionMockupService;
import com.bizplay.builder.solution.SolutionScreen;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 기능명세서 — 클론의 화면 md 를 읽어 절로 갈라 준다.
 *
 * <p>⛔ <b>화면 목록을 새로 읽지 않는다.</b> {@link SolutionMockupService} 가 이미 색인·md·git 이력을
 * 한 판에 훑어 캐시한다 — 여기서 또 훑으면 같은 274장을 두 번 읽는다.
 *
 * <p>⛔ <b>여기서 무엇도 쓰지 않는다.</b> 정본은 기획 저장소이고 빌더는 읽기만 한다.
 */
@Service
public class FeatureSpecService {

    private static final Logger log = LoggerFactory.getLogger(FeatureSpecService.class);

    private final SolutionMockupService solutions;
    private final SolutionScreenReader reader;

    public FeatureSpecService(SolutionMockupService solutions, SolutionScreenReader reader) {
        this.solutions = solutions;
        this.reader = reader;
    }

    public List<SolutionScreen> screens(String projectId) {
        return solutions.screens(projectId);
    }

    public Optional<SolutionScreen> screen(String projectId, String screenId) {
        return solutions.screen(projectId, screenId);
    }

    /**
     * 이 화면의 명세 파일이 실제로 있나.
     *
     * <p>⭐ <b>목록은 이것까지만 잰다</b>(원장 D6) — 기능 수를 세려면 274장 전문을 읽어야 하고
     * 그 비용이 목록을 그릴 때마다 난다. 파일이 있나는 {@code stat} 한 번이다.
     */
    public boolean hasDocument(String projectId, SolutionScreen screen) {
        try {
            Path file = documentPath(projectId, screen);
            return file != null && Files.isRegularFile(file);
        } catch (RuntimeException unreadable) {
            // ⚠ 파일 하나를 못 재는 것으로 목록 전체를 깨뜨리지 않는다(2026-08-27 코덱스 지적).
            log.warn("화면 명세가 있는지 못 쟀다: {}", screen.screenId(), unreadable);
            return false;
        }
    }

    /** 화면 md 를 읽어 절로 가른다. 파일이 없거나 못 읽으면 <b>빈 명세</b>다 — 던지지 않는다. */
    public FeatureSpecDocument document(String projectId, SolutionScreen screen) {
        Path file = documentPath(projectId, screen);
        if (file == null || !Files.isRegularFile(file) || !insideCloneForReal(projectId, file)) {
            return FeatureSpecDocument.empty();
        }
        try {
            return FeatureSpecDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            // ⚠ 못 읽는다고 화면을 깨뜨리지 않는다 — 고칠 사람은 기획팀이고 여기서 할 일이 없다.
            log.warn("화면 명세를 못 읽었다: {}", file, unreadable);
            return FeatureSpecDocument.empty();
        }
    }

    /**
     * 화면 가족 — <b>같은 부모를 둔 형제</b>다(원장 D4).
     *
     * <p>부모가 있으면 {@code [부모] + 부모의 자식들}, 없으면 {@code [나] + 내 자식들}.
     * 자료는 색인의 {@code ia.상위화면} 이고 실물에서 141장이 그 값을 갖고 있다.
     *
     * <p>⚠ <b>혼자면 빈 목록이다</b> — 자기 하나뿐인 탭 줄을 그리지 않는다.
     */
    public List<SolutionScreen> family(String projectId, SolutionScreen screen) {
        List<SolutionScreen> all = screens(projectId);
        String rootId = screen.parentScreenId() == null || screen.parentScreenId().isBlank()
                ? screen.screenId() : screen.parentScreenId();

        List<SolutionScreen> family = new ArrayList<>();
        all.stream()
                .filter(candidate -> candidate.screenId().equals(rootId))
                .findFirst()
                .ifPresent(family::add);
        all.stream()
                .filter(candidate -> rootId.equals(candidate.parentScreenId()))
                .forEach(family::add);

        return family.size() < 2 ? List.of() : family;
    }

    /**
     * 클론 안의 화면 md 경로.
     *
     * <p>⛔ <b>울타리를 반드시 잰다.</b> 화면ID 는 기획 저장소의 색인에서 오지만, 그 파일이 무엇을
     * 담을지는 우리가 정하지 않는다 — {@code ../} 가 섞이면 클론 밖을 가리킨다.
     */
    private Path documentPath(String projectId, SolutionScreen screen) {
        if (screen.system() == null || screen.system().isBlank()) {
            return null;
        }
        Path core = reader.coreRoot(projectId).toAbsolutePath().normalize();
        Path file = reader.fileInClone(projectId,
                screen.system() + "/pages/" + screen.screenId() + ".md")
                .toAbsolutePath().normalize();
        return file.startsWith(core) ? file : null;
    }

    /**
     * <b>읽기 직전</b>에 실제 경로로 울타리를 한 번 더 잰다.
     *
     * <p>⛔ <b>정규화만으로는 심볼릭 링크를 못 막는다</b>(2026-08-27 코덱스 지적) — 클론 안의 링크가
     * 밖을 가리키면 글자로는 울타리 안인데 <b>읽히는 것은 밖</b>이다.
     *
     * <p>⚠ <b>목록({@link #hasDocument})에서는 이것을 안 잰다.</b> 640장마다 실제 경로를 펴면
     * 목록 한 번에 파일 계통 호출이 곱절로 난다. 그리고 목록이 내는 것은 「있음」 배지 하나라
     * 밖의 내용이 새지 않는다 — <b>새는 자리는 읽는 여기 하나뿐</b>이다.
     */
    private boolean insideCloneForReal(String projectId, Path file) {
        try {
            Path realCore = reader.coreRoot(projectId).toAbsolutePath().normalize().toRealPath();
            return file.toRealPath().startsWith(realCore);
        } catch (IOException unresolvable) {
            log.warn("화면 명세 경로를 실제 경로로 못 폈다: {}", file, unresolvable);
            return false;
        }
    }
}
