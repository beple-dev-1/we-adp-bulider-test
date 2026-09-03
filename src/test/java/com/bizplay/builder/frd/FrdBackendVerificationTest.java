package com.bizplay.builder.frd;

import com.bizplay.builder.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면 외 구현의 <b>판정 방법</b>이 AI 출력에서 흘러들어오나.
 *
 * <p>설계: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}.
 *
 * <p>⭐ <b>왜 항목마다 필요한가</b> — 화면은 목업이 완료 조건 노릇을 하는데 화면 외 구현에는
 * 그것이 없다. ⛔ 전체 완료 조건({@code ACCEPTANCE_CRITERION})으로 갈음하면 항목 다섯 중
 * 어느 것이 남았는지 못 가른다.
 */
class FrdBackendVerificationTest extends AbstractDbTest {

    @Autowired FrdInterviewReader reader;

    @Test
    void 지시문_스키마가_판정_방법을_요구한다() {
        assertThat(ScreenPickWorker.OUTPUT_SCHEMA)
                .contains("\"verification\"")
                .contains("무엇으로 됐다고");
    }

    @Test
    void AI_가_적어_준_판정_방법을_읽는다() throws Exception {
        var turn = reader.read("""
                {"type":"RESULT","analysisSummary":"임시저장 범위를 정리했다","question":null,
                 "title":"임시저장",
                 "items":[{"requirement":"임시저장을 지원한다","nature":"DEVELOP","verdict":"SCREEN",
                   "screens":[{"screenId":"wv-appr-write","system":"webview",
                   "screenName":"결재 문서 작성","reason":"임시저장 버튼을 추가한다"}],"note":null}],
                 "backendChanges":[{"requirementSeq":1,"category":"API","target":"임시저장 API",
                    "changeDetail":"초안 저장 엔드포인트를 만든다","evidence":"화면 md 에 저장 흐름이 없다",
                    "verification":"같은 문서를 두 번 저장해도 한 줄만 남는다","required":true}],
                 "acceptanceCriteria":["임시저장한 문서가 목록에 남는다"],"openIssues":[],
                 "workMode":"FRD","workModeReason":"화면 변경이 있다","noScreenReason":null}
                """);

        var result = (FrdInterviewReader.Result) turn;
        assertThat(result.backendChanges()).hasSize(1);
        assertThat(result.backendChanges().get(0).verification())
                .isEqualTo("같은 문서를 두 번 저장해도 한 줄만 남는다");
    }

    @Test
    void 판정_방법이_없는_옛_출력도_그대로_읽힌다() throws Exception {
        var turn = reader.read("""
                {"type":"RESULT","analysisSummary":"정리했다","question":null,
                 "title":"임시저장",
                 "items":[{"requirement":"임시저장을 지원한다","nature":"DEVELOP","verdict":"SCREEN",
                   "screens":[{"screenId":"wv-appr-write","system":"webview",
                   "screenName":"결재 문서 작성","reason":"임시저장 버튼을 추가한다"}],"note":null}],
                 "backendChanges":[{"category":"API","target":"임시저장 API",
                    "changeDetail":"만든다","required":true}],
                 "acceptanceCriteria":[],"openIssues":[],
                 "workMode":"FRD","workModeReason":"까닭","noScreenReason":null}
                """);

        var result = (FrdInterviewReader.Result) turn;
        // ⚠ 널이 정상이다 — 값을 지어내면 계약서가 거짓을 말한다. 빈 것은 검증 경고로 잡는다.
        assertThat(result.backendChanges().get(0).verification()).isNull();
    }
}
