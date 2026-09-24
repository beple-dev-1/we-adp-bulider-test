package com.bizplay.builder.frd;

import com.bizplay.builder.solution.SolutionScreen;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면을 짚는 AI 가 적은 <b>시스템</b>을 기획 저장소의 값으로 바로잡나.
 *
 * <p>⭐ <b>시스템은 레포가 가진 값이다</b> — {@code manifest.json} 의 {@code systems[].id} 그대로이고
 * (V40 칸 설명), 화면마다의 값은 {@code index.json} 에 이미 있다. AI 에게 맡길 것이 아니다.
 * 2026-09-23 FRD-006 에서 AI 가 옛 규격의 예시(다른 사업의 {@code webview})를 적어, 초안이
 * 「화면을 만들 pages 폴더가 없습니다: webview」로 떨어졌다 — 브랜치 이름({@code dr/<시스템>/…})에도 번진다.
 */
class ScreenPickSystemsTest {

    private static final List<SolutionScreen> INDEX = List.of(
            screen("EXW-UWV-70-30-10-C", "EXW"),
            screen("BPY-MYAF-10-S", "BPY"));

    /** ⭐ 기존 화면은 색인의 시스템으로 덮는다 — AI 가 무엇을 적었든. */
    @Test
    void 기존_화면은_색인의_시스템으로_덮는다() {
        ScreenPickReader.Pick fixed = ScreenPickSystems.correct(pick(
                new ScreenPickReader.Picked("EXW-UWV-70-30-10-C", "webview", "회원가입", "성별 칸을 더한다")), INDEX);

        assertThat(fixed.screens()).singleElement()
                .extracting(ScreenPickReader.Picked::system).isEqualTo("EXW");
    }

    /** ⭐ AI 가 시스템을 비워 둬도 기존 화면이면 색인에서 채운다. */
    @Test
    void 비워_둔_시스템도_색인에서_채운다() {
        ScreenPickReader.Pick fixed = ScreenPickSystems.correct(pick(
                new ScreenPickReader.Picked("BPY-MYAF-10-S", null, "마이", "문구를 바꾼다")), INDEX);

        assertThat(fixed.screens().get(0).system()).isEqualTo("BPY");
    }

    /** ⚠ 새 화면은 색인에 없다 — 이 저장소에 있는 시스템이면 둔다. */
    @Test
    void 새_화면은_있는_시스템이면_둔다() {
        ScreenPickReader.Pick fixed = ScreenPickSystems.correct(pick(new ScreenPickReader.Picked(
                "성별-선택-팝업", "EXW", "성별 선택", "새로 만든다", true, "안내")), INDEX);

        assertThat(fixed.screens().get(0).system()).isEqualTo("EXW");
        assertThat(fixed.screens().get(0).newScreen()).isTrue();
    }

    /** ⛔ 새 화면에 이 저장소에 없는 시스템을 적었으면 비운다 — 지어낸 값으로 폴더를 찾으면 떨어진다. */
    @Test
    void 새_화면에_없는_시스템을_적었으면_비운다() {
        ScreenPickReader.Pick fixed = ScreenPickSystems.correct(pick(new ScreenPickReader.Picked(
                "성별-선택-팝업", "webview", "성별 선택", "새로 만든다", true, "안내")), INDEX);

        assertThat(fixed.screens().get(0).system()).isNull();
    }

    /** ⚠ 색인을 못 읽으면 바로잡을 근거가 없다 — 그대로 둔다. */
    @Test
    void 색인을_못_읽으면_그대로_둔다() {
        ScreenPickReader.Pick original = pick(
                new ScreenPickReader.Picked("EXW-UWV-70-30-10-C", "webview", "회원가입", "성별 칸을 더한다"));

        assertThat(ScreenPickSystems.correct(original, List.of())).isSameAs(original);
    }

    /** ⭐ 화면 말고는 한 글자도 안 바뀐다 — 제목 · 항목 · 화면 없음 사유는 그대로다. */
    @Test
    void 화면_말고는_그대로_둔다() {
        ScreenPickReader.Pick original = new ScreenPickReader.Pick("제목", List.of(),
                List.of(new ScreenPickReader.Picked("EXW-UWV-70-30-10-C", "webview", "회원가입", "이유")), "사유");

        ScreenPickReader.Pick fixed = ScreenPickSystems.correct(original, INDEX);

        assertThat(fixed.title()).isEqualTo("제목");
        assertThat(fixed.items()).isSameAs(original.items());
        assertThat(fixed.noScreenReason()).isEqualTo("사유");
        assertThat(fixed.screens().get(0).reason()).isEqualTo("이유");
    }

    private static ScreenPickReader.Pick pick(ScreenPickReader.Picked... screens) {
        return new ScreenPickReader.Pick("제목", List.of(), List.of(screens), null);
    }

    private static SolutionScreen screen(String id, String system) {
        return new SolutionScreen(id, id, system, "화면", null, null, null, null,
                null, null, List.of(), List.of(), null, List.of(), false, null);
    }
}
