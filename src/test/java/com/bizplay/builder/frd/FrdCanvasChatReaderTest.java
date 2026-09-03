package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FrdCanvasChatReaderTest {

    private final FrdCanvasChatReader reader = new FrdCanvasChatReader();

    @Test
    void 여러_화면_변경과_신규_화면_초안을_읽는다() throws Exception {
        FrdCanvasChatReader.Reply reply = reader.read("""
                {"type":"CHANGE","assistantMessage":"화면 흐름을 수정했습니다.",
                 "screens":[{"screenId":"screen-a","changes":["버튼을 추가했습니다."]}],
                 "newScreens":[{"draftKey":"complete","screenName":"완료 화면",
                   "baseScreenId":"screen-a","changes":["완료 안내를 구성했습니다."]}]}
                """);

        assertThat(reply.type()).isEqualTo(FrdCanvasChatReader.Type.CHANGE);
        assertThat(reply.screens()).singleElement().satisfies(screen -> {
            assertThat(screen.screenId()).isEqualTo("screen-a");
            assertThat(screen.changes()).containsExactly("버튼을 추가했습니다.");
        });
        assertThat(reply.newScreens()).singleElement().satisfies(screen -> {
            assertThat(screen.draftKey()).isEqualTo("complete");
            assertThat(screen.baseScreenId()).isEqualTo("screen-a");
        });
    }

    @Test
    void 안전하지_않은_신규_화면_초안키는_버린다() throws Exception {
        FrdCanvasChatReader.Reply reply = reader.read("""
                {"type":"CHANGE","assistantMessage":"확인했습니다.","screens":[],
                 "newScreens":[{"draftKey":"../outside","screenName":"화면",
                   "baseScreenId":"screen-a","changes":[]}]}
                """);

        assertThat(reply.newScreens()).isEmpty();
    }

    @Test
    void 같은_초안키로_화면을_중복_생성하지_않는다() throws Exception {
        FrdCanvasChatReader.Reply reply = reader.read("""
                {"type":"CHANGE","assistantMessage":"확인했습니다.","screens":[],
                 "newScreens":[
                   {"draftKey":"complete","screenName":"완료 화면", "baseScreenId":"screen-a","changes":[]},
                   {"draftKey":"complete","screenName":"중복 화면", "baseScreenId":"screen-a","changes":[]}
                 ]}
                """);

        assertThat(reply.newScreens()).singleElement()
                .extracting(FrdCanvasChatReader.NewScreen::screenName).isEqualTo("완료 화면");
    }

    @Test
    void 인터뷰_질문과_선택지를_읽어_대화에_보존한다() throws Exception {
        FrdCanvasChatReader.Reply reply = reader.read("""
                {"type":"INTERVIEW","assistantMessage":"수정 방향을 확인해 주세요.",
                 "screens":[],"newScreens":[],
                 "questions":[
                   {"id":"layout","prompt":"레이아웃 방향은 무엇인가요?","answerType":"SINGLE",
                    "options":["목록형","카드형"],"required":true},
                   {"id":"note","prompt":"추가 요청을 적어 주세요.","answerType":"TEXT",
                    "options":[],"required":false}
                 ]}
                """);

        assertThat(reply.type()).isEqualTo(FrdCanvasChatReader.Type.INTERVIEW);
        assertThat(reply.questions()).hasSize(2);
        assertThat(reply.questions().get(0).options()).containsExactly("목록형", "카드형");

        String stored = FrdCanvasInterviewContent.encode(reply.assistantMessage(), reply.questions());
        assertThat(FrdCanvasInterviewContent.decode(stored)).get().satisfies(content -> {
            assertThat(content.message()).isEqualTo("수정 방향을 확인해 주세요.");
            assertThat(content.questions()).hasSize(2);
        });
        assertThat(FrdCanvasInterviewContent.conversationText(stored))
                .contains("레이아웃 방향은 무엇인가요?", "목록형 / 카드형");
    }

    @Test
    void 질문이_없는_인터뷰_응답은_일반_답변으로_처리한다() throws Exception {
        FrdCanvasChatReader.Reply reply = reader.read("""
                {"type":"INTERVIEW","assistantMessage":"확인했습니다.",
                 "screens":[],"newScreens":[],"questions":[]}
                """);

        assertThat(reply.type()).isEqualTo(FrdCanvasChatReader.Type.ANSWER);
    }

    @Test
    void 전체_캔버스_대화도_Sonnet_중간_추론과_이전_세션을_사용한다() {
        var args = FrdCanvasChatWorker.claudeArgs(Path.of("C:/temp/canvas-input"),
                Path.of("C:/temp/canvas-drafts"), "79a07238-1ceb-4b01-bfdd-241183d0686b");

        assertThat(args).containsSubsequence("--model", "sonnet")
                .containsSubsequence("--effort", "low")
                .containsSequence("--resume", "79a07238-1ceb-4b01-bfdd-241183d0686b");
    }
}
