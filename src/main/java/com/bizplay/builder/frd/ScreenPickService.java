package com.bizplay.builder.frd;

import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 짚기의 <b>DB 토막</b>. ⛔ 여기에 프로세스를 띄우는 코드를 넣지 마라.
 */
@Service
public class ScreenPickService {

    private final FrdMapper frds;
    private final FrdScreenMapper screens;
    private final FrdItemMapper items;
    private final IdSequence ids;
    private final SolutionScreenReader solutionScreens;

    public ScreenPickService(FrdMapper frds, FrdScreenMapper screens, FrdItemMapper items,
                             IdSequence ids, SolutionScreenReader solutionScreens) {
        this.frds = frds;
        this.screens = screens;
        this.items = items;
        this.ids = ids;
        this.solutionScreens = solutionScreens;
    }

    /**
     * 짚은 것을 앉히고 상태를 넘긴다. <b>한 트랜잭션이다.</b>
     *
     * <p>⚠ 다시 짚으면 <b>이미 있는 화면은 그대로 두고</b> 새것만 더한다 —
     * 사람이 손본 것을 AI 가 되돌리면 안 된다. 유일 제약이 중복을 막는다.
     *
     * <p>⛔ <b>항목은 반대다 — 통째로 갈아 낀다.</b> 화면과 달리 사람이 손보는 것이 아니라
     * AI 가 읽은 것의 사본이고, 두 판을 섞으면 요구사항 원문과 차례가 어긋난다.
     */
    @Transactional
    public void savePick(String frdId, ScreenPickReader.Pick pick) {
        String soleSystem = soleSystemOf(pick);
        frds.updateAfterPick(frdId, pick.title(), soleSystem, pick.noScreenReason(),
                Frd.State.PICKED, null);

        /*
         * ⛔ **성격이 DEVELOP 인 항목의 화면만 여기 온다** — 거르는 것은 리더가 이미 했다
         *   (ScreenPickReader.Pick.screens()). 이 표는 **작업 단위**라 화면마다 to-be 목업을
         *   만든다: 「FAQ 삭제처리」처럼 고칠 것이 없는 화면을 앉히면 AI 가 헛일을 한다.
         *   근거로서의 화면은 adk_builder_frd_item.screen_ids 에 성격과 무관하게 다 남는다.
         */
        var existing = screens.selectByFrdId(frdId);
        Map<String, String> normalizedScreenIds = new LinkedHashMap<>();
        Set<String> retainedScreenRowIds = new LinkedHashSet<>();
        for (ScreenPickReader.Picked picked : pick.screens()) {
            String scopeChange = scopeChangeOf(picked, pick.items());
            FrdScreen selected = existing.stream()
                    .filter(screen -> screen.screenId().equals(picked.screenId()))
                    .findFirst().orElse(null);
            if (selected == null && screens.restoreExcluded(frdId, picked.screenId()) == 1) {
                selected = screens.selectByFrdId(frdId).stream()
                        .filter(screen -> screen.screenId().equals(picked.screenId()))
                        .findFirst().orElse(null);
            }
            if (selected != null) {
                retainedScreenRowIds.add(selected.id());
                if (picked.newScreen() && !selected.isNewScreen()) {
                    String temporaryId = TemporaryScreenId.of(selected.id());
                    screens.convertDiscoveredToDraft(selected.id(), temporaryId,
                            screenTypeOf(picked.screenType(), picked.screenId(), picked.screenName()));
                    normalizedScreenIds.put(picked.screenId(), temporaryId);
                }
                String system = nonBlank(picked.system()) ? picked.system() : soleSystem;
                if (system != null) {
                    screens.updateSystemCodeIfMissing(selected.id(), system);
                }
                screens.updateScopeChange(selected.id(), scopeChange);
                continue;
            }
            String screenRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
            if (picked.newScreen()) {
                String temporaryId = TemporaryScreenId.of(screenRowId);
                screens.insert(FrdScreen.draftedByAnalysis(screenRowId, frdId, temporaryId,
                        picked.screenName(), screenTypeOf(picked.screenType(), picked.screenId(), picked.screenName()),
                        null, nonBlank(picked.system()) ? picked.system() : soleSystem,
                        picked.reason()));
                normalizedScreenIds.put(picked.screenId(), temporaryId);
            } else {
                screens.insert(FrdScreen.pickedIn(screenRowId, frdId,
                        picked.screenId(), picked.screenName(), picked.screenId(), null,
                        picked.reason(), picked.system()));
            }
            screens.updateScopeChange(screenRowId, scopeChange);
        }

        /*
         * 재분석 결과에서 빠진 AI 전용 미작업 화면은 활성 작업 목록에서 내린다. 사용자가 직접
         * 고른 화면이나 이미 목업 작업이 시작된 화면은 보존한다.
         */
        existing.stream()
                .filter(screen -> !retainedScreenRowIds.contains(screen.id()))
                .filter(ScreenPickService::canExcludeAfterReanalysis)
                .forEach(screen -> screens.excludeById(screen.id()));

        items.deleteByFrdId(frdId);
        int seq = 0;
        for (ScreenPickReader.Item item : pick.items()) {
            seq++;
            List<String> screenIds = item.screenIds().stream()
                    .map(id -> normalizedScreenIds.getOrDefault(id, id)).toList();
            items.insert(FrdItem.of(ids.next(IdSequence.Kind.FRD_ITEM), frdId, seq,
                    item.requirement(), FrdItem.Nature.valueOf(item.nature().name()),
                    FrdItem.Verdict.valueOf(item.verdict().name()), screenIds, item.note()));
        }
    }

    /**
     * 과거 분석 결과가 신규 화면을 기존 화면처럼 저장한 경우, 파일 없음이 확인된 시점에 TMP 화면으로 복구한다.
     * 실제 기존 화면의 누락과 구분하기 위해 AI 선택 근거가 있고 기준 화면이 자기 자신인 행만 바꾼다.
     */
    @Transactional
    public FrdScreen recoverDiscoveredNewScreen(String frdScreenId) {
        FrdScreen screen = screens.selectById(frdScreenId);
        if (screen == null || screen.isNewScreen() || !nonBlank(screen.pickReason())
                || !screen.screenId().equals(screen.baseScreenId())) {
            return screen;
        }
        String oldScreenId = screen.screenId();
        Frd frd = frds.selectById(screen.frdId());
        if (frd == null || solutionScreens.read(frd.projectId()).stream()
                .anyMatch(candidate -> oldScreenId.equals(candidate.screenId()))) {
            return screen;
        }
        String temporaryId = TemporaryScreenId.of(screen.id());
        int changed = screens.convertDiscoveredToDraft(screen.id(), temporaryId,
                screenTypeOf(screen.screenType(), screen.screenId(), screen.screenName()));
        if (changed == 1) {
            items.replaceScreenId(screen.frdId(), oldScreenId, temporaryId);
        }
        return screens.selectById(frdScreenId);
    }

    static String screenTypeOf(String declared, String screenId, String screenName) {
        if (nonBlank(declared)) return declared.strip();
        String clue = ((screenId == null ? "" : screenId) + " "
                + (screenName == null ? "" : screenName)).toLowerCase();
        if (clue.contains("detail") || clue.contains("상세")) return "상세";
        if (clue.contains("register") || clue.contains("create") || clue.contains("등록")) return "등록";
        if (clue.contains("modify") || clue.contains("edit") || clue.contains("update") || clue.contains("수정")) return "수정";
        if (clue.contains("list") || clue.contains("목록") || clue.contains("관리")) return "목록";
        return "안내";
    }

    /** 화면 자체 설명을 우선하고, 누락됐을 때는 연결된 모든 항목의 정리 내용을 순서대로 보완한다. */
    private static String scopeChangeOf(ScreenPickReader.Picked picked, List<ScreenPickReader.Item> items) {
        if (nonBlank(picked.reason())) {
            return picked.reason().strip();
        }
        Set<String> changes = new LinkedHashSet<>();
        for (ScreenPickReader.Item item : items) {
            if (!item.screenIds().contains(picked.screenId())) {
                continue;
            }
            if (nonBlank(item.note())) {
                changes.add(item.note().strip());
            } else if (nonBlank(item.requirement())) {
                changes.add(item.requirement().strip());
            }
        }
        return changes.isEmpty() ? null : String.join(System.lineSeparator(), changes);
    }

    private static boolean canExcludeAfterReanalysis(FrdScreen screen) {
        return !screen.isUserSelected()
                && screen.state() == FrdScreen.State.WAITING
                && !nonBlank(screen.html())
                && !nonBlank(screen.changes());
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * FRD 한 줄의 「시스템」 칸에 적을 값.
     *
     * <p>⛔ <b>걸치면 비운다.</b> 하나의 요구사항이 webview 와 backoffice 에 같이 걸리는 것이
     * 정상인데(웹뷰에 보이는 것을 백오피스에서 끄는 일이 흔하다) 하나를 골라 적으면
     * 다른 하나가 화면에서 거짓말이 된다. 설계서가 「비는 것은 정상」이라고 적어 뒀다 —
     * 시스템마다의 값은 {@code adk_builder_frd_screen.system_code} 가 들고 있다.
     */
    private static String soleSystemOf(ScreenPickReader.Pick pick) {
        Set<String> systems = new LinkedHashSet<>();
        for (ScreenPickReader.Picked picked : pick.screens()) {
            if (nonBlank(picked.system())) {
                systems.add(picked.system());
            }
        }
        return systems.size() == 1 ? systems.iterator().next() : null;
    }

    /**
     * ⛔ {@code ANALYZING} 으로 되돌리지 마라 — 「해 봤는데 안 됐다」와 「돌고 있다」가 섞이면
     * 화면이 다시 누르라고 말할 수 없게 된다.
     */
    @Transactional
    public void markFailed(String frdId, String why) {
        frds.updateAfterPick(frdId, null, null, null, Frd.State.ANALYSIS_FAILED, why);
    }
}
