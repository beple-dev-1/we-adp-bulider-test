package com.bizplay.builder.frd;

import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FrdScreenChatTest {

    @Test
    void 참고_이미지를_현재_구성요소로_재구성하도록_지시한다() {
        FrdScreen screen = FrdScreen.picked("FS00001", "FR00001", "wv-appr-write",
                "결재 문서 작성", "wv-appr-write", null, null);

        String prompt = FrdScreenChatWorker.instruction("C:/temp/대화.md",
                "core/webview/pages/wv-appr-write.html", "C:/temp/new-screen.html",
                null, "C:/temp/참고이미지.png", screen);

        org.assertj.core.api.Assertions.assertThat(prompt)
                .contains("C:/temp/참고이미지.png")
                .contains("이미지를 그대로 삽입하지 말고")
                .contains("편집 가능한 화면을 구성하라");
    }

    @Test
    void 선택한_수정_영역은_별도_자료로_AI에게_전달한다() {
        FrdScreen screen = FrdScreen.picked("FS00001", "FR00001", "wv-appr-write",
                "결재 문서 작성", "wv-appr-write", null, null);

        String prompt = FrdScreenChatWorker.instruction("C:/temp/대화.md",
                "core/webview/pages/wv-appr-write.html", "C:/temp/new-screen.html",
                "C:/temp/선택영역.json", screen);

        assertThat(prompt).contains("사용자가 선택한 수정 영역")
                .contains("C:/temp/선택영역.json")
                .contains("selector, label, text, html")
                .contains("selectionType이 RECTANGLE")
                .contains("selectionType이 MARKER")
                .contains("가장 가까운 요소만 수정");
    }

    @Test
    void 화면_수정_대화는_Sonnet과_중간_추론을_사용하고_대상_파일만_편집한다() {
        var args = FrdScreenChatWorker.claudeArgs(Path.of("C:/temp/chat-input"),
                "core/webview/pages/wv-appr-write.html",
                Path.of("C:/temp/chat-input/new-screen.html"));

        assertThat(args).containsSubsequence("--model", "sonnet")
                .containsSubsequence("--effort", "low");
        assertThat(args).anyMatch(value -> value.contains(
                "Edit(/core/webview/pages/wv-appr-write.html)"));
        assertThat(args).anyMatch(value -> value.contains(
                "Edit(//c/temp/chat-input/new-screen.html)"));
        assertThat(args).noneMatch(value -> value.contains("Write("));
        assertThat(args.get(args.size() - 2)).isEqualTo("--add-dir");
        assertThat(args.get(args.size() - 1)).isEqualTo("C:\\temp\\chat-input");
    }

    @Test
    void 전체_캔버스는_신규_화면_폴더만_재귀적으로_수정하도록_허용한다() {
        var args = FrdCanvasChatWorker.claudeArgs(Path.of("C:/temp/canvas-input"),
                Path.of("C:/temp/new-screens"), (String) null);

        assertThat(args).anyMatch(value -> value.contains("Edit(//c/temp/new-screens/**)"));
        assertThat(args).noneMatch(value -> value.contains("Write("));
    }

    @Test
    void 이전_Claude_세션이_있으면_같은_화면의_대화를_이어간다() {
        var args = FrdScreenChatWorker.claudeArgs(Path.of("C:/temp/chat-input"),
                "core/webview/pages/wv-appr-write.html",
                Path.of("C:/temp/chat-input/new-screen.html"),
                "79a07238-1ceb-4b01-bfdd-241183d0686b");

        assertThat(args).containsSequence("--resume", "79a07238-1ceb-4b01-bfdd-241183d0686b");
    }

    @Test
    void AI가_답변을_생략해도_안전한_기본_답변으로_대화를_완료한다() throws Exception {
        FrdScreenChatReader.Reply reply = new FrdScreenChatReader()
                .read("{\"changes\":[\"안내 문구 추가\"]}");

        assertThat(reply.type()).isEqualTo(FrdScreenChatReader.Type.EDIT);
        assertThat(reply.changes()).containsExactly("안내 문구 추가");
        assertThat(reply.assistantMessage()).isEqualTo("요청한 내용을 화면에 반영했습니다.");
    }

    @Test
    void 화면에_관한_질문은_수정이_아닌_답변으로_읽는다() throws Exception {
        FrdScreenChatReader.Reply reply = new FrdScreenChatReader().read("""
                {"type":"ANSWER","changes":[],"assistantMessage":"상품권 선택 레이어가 열립니다."}
                """);

        assertThat(reply.type()).isEqualTo(FrdScreenChatReader.Type.ANSWER);
        assertThat(reply.changes()).isEmpty();
        assertThat(reply.assistantMessage()).contains("상품권 선택 레이어");
    }

    @Test
    void 신규_화면_요청은_화면_식별자를_함께_읽는다() throws Exception {
        FrdScreenChatReader.Reply reply = new FrdScreenChatReader().read("""
                {"type":"CREATE_SCREEN","changes":["상품권 선택 화면 추가"],
                 "assistantMessage":"새 화면을 만들었습니다.",
                 "newScreen":{"screenId":"wv-gift-select","screenName":"상품권 선택"}}
                """);

        assertThat(reply.type()).isEqualTo(FrdScreenChatReader.Type.CREATE_SCREEN);
        assertThat(reply.newScreen().screenId()).isEqualTo("wv-gift-select");
        assertThat(reply.newScreen().screenName()).isEqualTo("상품권 선택");
    }

    @Test
    void AI_답변의_문자열_개행을_실제_개행으로_바꾼다() throws Exception {
        FrdScreenChatReader.Reply reply = new FrdScreenChatReader().read("""
                {"type":"ANSWER","changes":[],"assistantMessage":"제목\\\\n\\\\n- 검색 영역\\\\n- 선택 목록"}
                """);

        assertThat(reply.assistantMessage()).isEqualTo("제목\n\n- 검색 영역\n- 선택 목록");
    }

    @Test
    void 화면_대화_실패는_API_원문_대신_원인과_다음_행동을_보여준다() {
        ClaudeResult busy = new ClaudeResult(1, true, "api_error", 529, "API Error: overloaded");
        ClaudeResult rateLimited = new ClaudeResult(1, true, "api_error", 429, "rate limit");
        ClaudeResult expired = new ClaudeResult(1, true, "api_error", null, "Not logged in");

        assertThat(FrdScreenChatWorker.failureMessage(busy))
                .contains("AI 서버가 혼잡").contains("다시 요청").doesNotContain("529", "API Error");
        assertThat(FrdScreenChatWorker.failureMessage(rateLimited))
                .contains("Claude 계정", "사용 한도 또는 요청 제한", "제한이 해제된 뒤")
                .doesNotContain("AI 서버가 혼잡", "429", "rate limit");
        assertThat(FrdCanvasChatWorker.failureMessage(rateLimited))
                .contains("Claude 계정", "사용 한도 또는 요청 제한", "제한이 해제된 뒤")
                .doesNotContain("AI 서버가 혼잡", "429", "rate limit");
        assertThat(FrdCanvasChatWorker.failureMessage(expired))
                .contains("계정 연결이 만료").contains("다시 연결").doesNotContain("Not logged in");
        assertThat(ScreenMockupWorker.failureMessage(ClaudeResult.timedOut()))
                .contains("시간이 초과").contains("다시 시도");
    }

    @Test
    void 대화_오류_카드는_과거의_기술_오류도_화면에서_차단한다() throws Exception {
        String script = Files.readString(Path.of("src/main/resources/static/js/frd-chat-window.js"));
        String canvasScript = Files.readString(Path.of("src/main/resources/static/js/frd-canvas-chat-window.js"));
        String style = Files.readString(Path.of("src/main/resources/static/css/screens.css"));

        assertThat(script).contains("safeFailure(item.failure").contains("apiStatus|terminalReason|exitCode");
        assertThat(canvasScript).contains("safeFailure(item.failure").contains("입력한 요청은 대화에 남아 있습니다");
        assertThat(style).contains(".wm-ai-chat-message__guide").contains(".wm-ai-chat-message--cancelled");
    }

    @Test
    void 실패한_화면_대화에는_완료_안내를_표시하지_않는다() throws Exception {
        String detail = Files.readString(Path.of("src/main/resources/templates/artifacts/frd.html"));

        assertThat(detail)
                .contains("const finishedMessage = status.messages.find(message => message.id === chatActiveId)")
                .contains("finishedMessage?.state === 'DONE'")
                .doesNotContain("if (chatActiveId && !status.active) {\n"
                        + "          chatNotice = (chatActiveScreenName || '이전')");
    }

    @Test
    void 상세_채팅을_닫으면_선택_영역을_해제하고_직접_수정은_텍스트_조각을_찾는다() throws Exception {
        String detail = Files.readString(Path.of("src/main/resources/templates/artifacts/frd.html"));

        assertThat(detail)
                .contains("const closeChat = (clearSelection = true) =>")
                .contains("if (clearSelection) clearSelectedRegion()")
                .contains("closedPopupScreenId === currentLink()?.dataset.screenId")
                .contains("const directTextNodeAtPoint = event =>")
                .contains("textNode.replaceWith(editor)")
                .contains("!name.startsWith('builder-direct-edit-')")
                .doesNotContain("문구를 바로 수정하세요.");
    }
}
