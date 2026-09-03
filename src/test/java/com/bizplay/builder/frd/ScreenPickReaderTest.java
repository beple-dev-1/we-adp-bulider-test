package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 짚기 결과를 <b>서버가 검사해서</b> 받는다.
 *
 * <p>⛔ 검사 없이 저장하면 화면ID 가 빈 줄이 앉고, 그 줄은 목업을 만들 수도 지울 수도 없다.
 *
 * <p>⭐ <b>2026-08-18 실측이 계약을 넓혔다.</b> 요구사항 6건짜리가 화면 1장으로 끝나고
 * 나머지 다섯이 <b>아무 말 없이 사라진</b> 것을 봤다 — 그래서 {@code items} 가 생겼다.
 * 항목마다 판정을 받으면 조용한 누락이 화면에 드러난다.
 */
class ScreenPickReaderTest {

    private final ScreenPickReader reader = new ScreenPickReader();

    @Test
    void 인터뷰에서_확정한_신규_화면의_여부와_유형을_읽는다() throws IOException {
        ScreenPickReader.Pick pick = reader.read("""
                {"title":"보고서 관리",
                 "items":[{"requirement":"보고서를 등록한다","nature":"DEVELOP","verdict":"SCREEN",
                   "screens":[{"screenId":"bo-report-register","system":"backoffice",
                     "screenName":"보고서 등록","newScreen":true,"screenType":"등록",
                     "reason":"등록 화면을 새로 만든다"}]}]}
                """);

        assertThat(pick.screens()).singleElement().satisfies(screen -> {
            assertThat(screen.newScreen()).isTrue();
            assertThat(screen.screenType()).isEqualTo("등록");
        });
    }

    @Test
    void 울타리를_두른_출력에서도_읽는다() throws IOException {
        String output = """
                ```json
                {"title":"전자결재 상신 임시저장 지원",
                 "items":[{"requirement":"임시저장을 지원한다","verdict":"SCREEN",
                           "screenIds":["wv-appr-write"],"note":"작성 화면이다"}],
                 "screens":[{"screenId":"wv-appr-write","system":"webview","screenName":"결재 문서 작성",
                             "reason":"상단에 임시저장 버튼이 없습니다"}],
                 "noScreenReason":null}
                ```
                """;

        ScreenPickReader.Pick pick = reader.read(output);

        assertThat(pick.title()).isEqualTo("전자결재 상신 임시저장 지원");
        assertThat(pick.screens()).singleElement().satisfies(screen -> {
            assertThat(screen.screenId()).isEqualTo("wv-appr-write");
            assertThat(screen.system()).isEqualTo("webview");
        });
    }

    /**
     * ⭐ <b>실물이 이 모양으로 왔다 (2026-08-18 두 번째 실측).</b> 화면은 <b>항목 안에 중첩</b>되고
     * 항목 이름은 {@code title} 이다 — 그리고 <b>모델 쪽이 옳다.</b>
     *
     * <p>처음 계약은 같은 화면을 두 곳(항목의 {@code screenIds} 와 최상위 {@code screens})에
     * 적으라 했고 이름도 두 층에서 겹쳤다({@code title}·{@code screens}) — 모델은 그것을
     * 하나로 합쳤다. <b>중복을 요구한 계약이 틀린 것이다.</b>
     */
    @Test
    void 화면이_항목_안에_중첩돼_와도_읽는다() throws IOException {
        ScreenPickReader.Pick pick = reader.read("""
                {"title":"고유가 피해지원금 종료처리 과업",
                 "items":[
                   {"title":"전체 메뉴 : 고유가 피해지원금 경로 히든처리","verdict":"SCREEN",
                    "screens":[{"screenId":"wv-modal-all-menu","system":"webview",
                                "screenName":"전체메뉴 (전체메뉴 모달)","reason":"e16 앵커가 살아 있다"}]},
                   {"title":"가맹점 찾기 : 안내 문구 원복처리","verdict":"NOT_INDEXED",
                    "screens":[],"note":"wv-merc-search-main 이 색인에 없다"}]}""");

        assertThat(pick.items()).hasSize(2);
        assertThat(pick.items().get(0).requirement()).isEqualTo("전체 메뉴 : 고유가 피해지원금 경로 히든처리");
        assertThat(pick.items().get(0).screenIds()).containsExactly("wv-modal-all-menu");
        assertThat(pick.items().get(1).verdict()).isEqualTo(ScreenPickReader.Verdict.NOT_INDEXED);
        assertThat(pick.screens()).singleElement().satisfies(screen -> {
            assertThat(screen.screenId()).isEqualTo("wv-modal-all-menu");
            assertThat(screen.system()).isEqualTo("webview");
            assertThat(screen.screenName()).isEqualTo("전체메뉴 (전체메뉴 모달)");
            assertThat(screen.reason()).contains("e16");
        });
    }

    /**
     * ⭐ <b>실물이 이 모양으로 왔다 (2026-08-18 세 번째 실측).</b> 요구사항 원문이 「웹뷰」·
     * 「고피지 지급시스템」 두 표제로 나뉘어 있으니 모델이 {@code sections} 로 <b>한 겹 더 감쌌고</b>,
     * 항목 이름은 {@code text}·화면은 {@code id}·{@code name} 이었다.
     *
     * <p>모양을 못박는 것은 {@code --json-schema} 의 몫이다 — 이 시험은 <b>보험</b>이다.
     * {@code verdict} 를 든 객체를 <b>껍데기가 몇 겹이든 찾아낸다</b>: 그 표식은 매우 특징적이고,
     * 값을 지어내는 것이 아니라 <b>있는 값을 찾아 읽는 것</b>이라 「모르면 실패」와 부딪히지 않는다.
     */
    @Test
    void 껍데기로_한_겹_더_감싸도_항목을_찾아낸다() throws IOException {
        ScreenPickReader.Pick pick = reader.read("""
                {"title":"고유가 피해지원금 종료처리",
                 "sections":[
                   {"section":"웹뷰",
                    "items":[{"no":1,"text":"전체 메뉴 : 경로 히든처리","verdict":"SCREEN",
                              "screens":[{"id":"wv-modal-all-menu","name":"전체메뉴",
                                          "system":"webview","reason":"e16 앵커가 있다"}]}]},
                   {"section":"고피지 지급시스템",
                    "items":[{"no":2,"text":"결제통지 알림톡 중단","verdict":"NO_SCREEN",
                              "screens":[],"note":"발송 규칙이다"}]}]}""");

        assertThat(pick.items()).hasSize(2);
        assertThat(pick.items().get(0).requirement()).isEqualTo("전체 메뉴 : 경로 히든처리");
        assertThat(pick.items().get(1).verdict()).isEqualTo(ScreenPickReader.Verdict.NO_SCREEN);
        assertThat(pick.screens()).singleElement().satisfies(screen -> {
            assertThat(screen.screenId()).isEqualTo("wv-modal-all-menu");
            assertThat(screen.screenName()).isEqualTo("전체메뉴");
            assertThat(screen.system()).isEqualTo("webview");
        });
    }

    /** ⚠ 같은 화면이 두 항목에 걸리면 <b>한 번만</b> 센다 — 화면 표에 유일 제약이 걸려 있다. */
    @Test
    void 두_항목이_같은_화면을_가리키면_한_번만_센다() throws IOException {
        ScreenPickReader.Pick pick = reader.read("""
                {"title":"제목",
                 "items":[
                   {"title":"첫째 요구","verdict":"SCREEN",
                    "screens":[{"screenId":"wv-a","system":"webview","screenName":"ㄱ","reason":"까닭 하나"}]},
                   {"title":"둘째 요구","verdict":"SCREEN",
                    "screens":[{"screenId":"wv-a","system":"webview","screenName":"ㄱ","reason":"까닭 둘"},
                               {"screenId":"wv-b","system":"webview","screenName":"ㄴ","reason":"까닭 셋"}]}]}""");

        assertThat(pick.screens()).extracting(ScreenPickReader.Picked::screenId)
                .containsExactly("wv-a", "wv-b");
        assertThat(pick.screens().get(0).reason())
                .as("같은 화면에 걸린 요구사항별 수정 내용을 모두 보존한다")
                .contains("까닭 하나", "까닭 둘");
        assertThat(pick.items().get(1).screenIds()).containsExactly("wv-a", "wv-b");
    }

    /** ⭐ 요구사항 항목마다 판정 하나. 이것이 조용한 누락을 드러내는 유일한 자리다. */
    @Test
    void 항목마다_판정을_읽는다() throws IOException {
        ScreenPickReader.Pick pick = reader.read("""
                {"title":"고유가 피해지원금 종료처리",
                 "items":[
                   {"requirement":"전체 메뉴 경로 히든처리","verdict":"SCREEN",
                    "screenIds":["wv-modal-all-menu"],"note":"e16 앵커가 살아 있다"},
                   {"requirement":"결제통지 알림톡 중단","verdict":"NO_SCREEN",
                    "screenIds":[],"note":"dino-api-lspn-api 의 발송 규칙이다"},
                   {"requirement":"가맹점 찾기 문구 원복","verdict":"NOT_INDEXED",
                    "screenIds":[],"note":"wv-merc-search-main 이 색인에 없다"}],
                 "screens":[{"screenId":"wv-modal-all-menu","system":"webview",
                             "screenName":"전체메뉴","reason":"경로가 살아 있다"}],
                 "noScreenReason":null}""");

        assertThat(pick.items()).hasSize(3);
        assertThat(pick.items().get(0).verdict()).isEqualTo(ScreenPickReader.Verdict.SCREEN);
        assertThat(pick.items().get(0).screenIds()).containsExactly("wv-modal-all-menu");
        assertThat(pick.items().get(1).verdict()).isEqualTo(ScreenPickReader.Verdict.NO_SCREEN);
        assertThat(pick.items().get(2).verdict()).isEqualTo(ScreenPickReader.Verdict.NOT_INDEXED);
        assertThat(pick.items().get(2).note()).contains("색인에 없다");
    }

    /**
     * ⛔ <b>모르는 판정을 통과시키지 마라.</b> 셋 중 하나가 아니면 그 항목이 무슨 뜻인지
     * 아무도 모르는데 화면에는 앉는다.
     */
    @Test
    void 모르는_판정은_통째로_거절한다() {
        assertThatThrownBy(() -> reader.read("""
                {"title":"제목","items":[{"requirement":"무엇","verdict":"MAYBE","screenIds":[]}],
                 "screens":[]}"""))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("MAYBE");
    }

    /** ⛔ 항목이 하나도 없으면 요구사항을 안 읽은 것이다 — 조용한 누락의 가장 큰 꼴이다. */
    @Test
    void 항목이_하나도_없으면_거절한다() {
        assertThatThrownBy(() -> reader.read("""
                {"title":"제목","items":[],"screens":[]}"""))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("항목");
    }

    /**
     * ⭐ <b>실물이 이 모양으로 왔다(2026-08-18).</b> {@code index.json} 의 {@code screens} 가
     * 화면ID 를 키로 한 객체라 모델이 방금 읽은 파일을 따라한다. Jackson 이 값만 훑어
     * 「화면ID 가 빈 줄」로 보이던 것을 <b>무엇이 틀렸는지 말하는 오류</b>로 바꾼다.
     */
    @Test
    void 화면을_객체로_내면_배열이_아니라고_말한다() {
        assertThatThrownBy(() -> reader.read("""
                {"title":"제목","items":[{"requirement":"무엇","verdict":"SCREEN",
                                          "screenIds":["wv-appr-write"]}],
                 "screens":{"wv-appr-write":{"screenName":"이름","reason":"까닭"}}}"""))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("배열");
    }

    /** 화면 작업 대상에는 무엇을 바꾸는지 반드시 있어야 한다. */
    @Test
    void 화면별_수정_내용이_없으면_거절한다() {
        assertThatThrownBy(() -> reader.read("""
                {"title":"제목","items":[{"requirement":"무엇","verdict":"SCREEN",
                                          "screens":["wv-appr-write"]}]}"""))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("수정 내용(reason)");
    }

    /** ⛔ 그래도 <b>모양이 아예 다른 것</b>은 거절한다 — 숫자가 화면ID 일 수는 없다. */
    @Test
    void 화면이_객체도_문자열도_아니면_거절한다() {
        assertThatThrownBy(() -> reader.read("""
                {"title":"제목","items":[{"requirement":"무엇","verdict":"SCREEN",
                                          "screens":[42]}]}"""))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("객체가 아니다");
    }

    @Test
    void 화면이_비고_까닭이_있으면_화면_없는_요건이다() throws IOException {
        ScreenPickReader.Pick pick = reader.read("""
                {"title":"야간 정산 배치 주기 변경",
                 "items":[{"requirement":"배치 주기를 바꾼다","verdict":"NO_SCREEN",
                           "screenIds":[],"note":"설정값이다"}],
                 "screens":[],
                 "noScreenReason":"화면이 아니라 배치 주기 설정 변경입니다"}""");

        assertThat(pick.screens()).isEmpty();
        assertThat(pick.noScreenReason()).contains("배치");
    }

    /** ⚠ 몇 번째 칸이 비었는지 말한다 — 안 그러면 열 장 중 어디가 문제인지 못 찾는다. */
    @Test
    void 화면ID_가_빈_줄은_몇_번째인지_말하고_거절한다() {
        assertThatThrownBy(() -> reader.read("""
                {"title":"제목","items":[{"requirement":"무엇","verdict":"SCREEN","screenIds":[]}],
                 "screens":[{"screenId":"wv-ok","screenName":"괜찮다"},
                            {"screenId":"  ","screenName":"이름"}]}"""))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("2번째");
    }

    @Test
    void 제목이_없으면_거절한다() {
        assertThatThrownBy(() -> reader.read("""
                {"items":[{"requirement":"무엇","verdict":"NO_SCREEN","screenIds":[]}],"screens":[]}"""))
                .isInstanceOf(IOException.class);
    }

    /** ⛔ {@code system_code} 는 {@code varchar(50)}·{@code screen_id} 는 {@code varchar(100)} 이다. */
    @Test
    void 시스템과_화면ID_가_열_폭을_넘으면_잘라서_받는다() throws IOException {
        String longSystem = "가".repeat(60);
        String longScreenId = "나".repeat(110);
        ScreenPickReader.Pick pick = reader.read("""
                {"title":"제목","items":[{"requirement":"무엇","verdict":"SCREEN","screenIds":[]}],
                 "screens":[{"screenId":"%s","system":"%s","screenName":"이름",
                              "reason":"화면 표시 내용을 바꾼다"}]}"""
                .formatted(longScreenId, longSystem));

        assertThat(pick.screens()).singleElement().satisfies(screen -> {
            assertThat(screen.screenId()).hasSize(100).isEqualTo(longScreenId.substring(0, 100));
            assertThat(screen.system()).hasSize(50).isEqualTo(longSystem.substring(0, 50));
        });
    }

    /** ⚠ 열 장이 상한이다 — 넘으면 통째로 거절한다. */
    @Test
    void 짚은_화면이_열_장을_넘으면_통째로_거절한다() {
        StringBuilder screens = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            if (i > 1) {
                screens.append(",");
            }
            screens.append("""
                    {"screenId":"wv-screen-%d","screenName":"화면 %d"}""".formatted(i, i));
        }

        assertThatThrownBy(() -> reader.read("""
                {"title":"제목","items":[{"requirement":"무엇","verdict":"SCREEN","screenIds":[]}],
                 "screens":[%s]}""".formatted(screens)))
                .isInstanceOf(IOException.class);
    }
}
