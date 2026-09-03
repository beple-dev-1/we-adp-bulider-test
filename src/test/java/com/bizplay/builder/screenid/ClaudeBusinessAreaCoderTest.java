package com.bizplay.builder.screenid;

import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.project.ProjectPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeBusinessAreaCoderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void AI_가_준_세글자를_slug_에_붙인다() throws Exception {
        assertThat(codesFrom("""
                {"codes": {"merchant": "MRC", "customer": "CUS"}}
                """)).containsExactlyInAnyOrderEntriesOf(
                Map.of("merchant", "MRC", "customer", "CUS"));
    }

    @Test
    void 세글자_대문자가_아니면_XXX_로_바꾼다() throws Exception {
        assertThat(codesFrom("""
                {"codes": {"merchant": "Merchant", "customer": "cu"}}
                """))
                .containsEntry("merchant", "XXX")
                .containsEntry("customer", "XXX");
    }

    @Test
    void 같은_세글자를_두_번_주면_뒤엣것을_XXX_로_민다() throws Exception {
        // DB 부분 유일 인덱스가 막기 전에 여기서 먼저 걷어낸다 — 채번 한 판이 통째로 깨지지 않게.
        assertThat(codesFrom("""
                {"codes": {"merchant": "MRC", "customer": "MRC"}}
                """))
                .containsEntry("merchant", "MRC")
                .containsEntry("customer", "XXX");
    }

    @Test
    void 빠뜨린_업무영역도_XXX_로_채운다() throws Exception {
        assertThat(codesFrom("""
                {"codes": {"merchant": "MRC"}}
                """))
                .containsEntry("merchant", "MRC")
                .containsEntry("customer", "XXX");
    }

    @Test
    void 답이_아예_없으면_전부_XXX_다() {
        assertThat(BusinessAreaCodes.of(null, areas()))
                .containsEntry("merchant", "XXX")
                .containsEntry("customer", "XXX");
    }

    @Test
    void 업무영역이_없으면_빈_표를_낸다() {
        ClaudeBusinessAreaCoder coder = new ClaudeBusinessAreaCoder(null, null);
        assertThat(coder.codesOf("0000001", "0000002", Map.of())).isEmpty();
    }

    @Test
    void 자격을_쓸_수_없으면_던지지_않고_전부_XXX_다() {
        // 클론은 관리자가 돌리는데 그 계정에 Claude 연결이 없을 수 있다 — 그때 던지면 클론 흐름이 죽는다.
        ClaudeBusinessAreaCoder coder = new ClaudeBusinessAreaCoder(null, null);
        assertThat(coder.codesOf("0000001", "0000002", areas()))
                .containsEntry("merchant", "XXX")
                .containsEntry("customer", "XXX");
    }

    @Test
    void AI_응답이_JSON_이_아니면_던지지_않고_전부_XXX_다() throws Exception {
        ClaudeCredentialRunner credentials = mock(ClaudeCredentialRunner.class);
        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir(any())).thenReturn(Path.of("."));
        when(credentials.run(any(), any(), any(), any(), anyList(), any(), any()))
                .thenReturn(Optional.of(new ClaudeResult(0, false, "success", null, "not json at all")));

        ClaudeBusinessAreaCoder coder = new ClaudeBusinessAreaCoder(credentials, paths);
        assertThat(coder.codesOf("0000001", "0000002", areas()))
                .containsEntry("merchant", "XXX")
                .containsEntry("customer", "XXX");
    }

    @Test
    void AI_가_실패라고_답하면_전부_XXX_다() throws Exception {
        ClaudeCredentialRunner credentials = mock(ClaudeCredentialRunner.class);
        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir(any())).thenReturn(Path.of("."));
        when(credentials.run(any(), any(), any(), any(), anyList(), any(), any()))
                .thenReturn(Optional.of(new ClaudeResult(1, true, "error", null, "")));

        ClaudeBusinessAreaCoder coder = new ClaudeBusinessAreaCoder(credentials, paths);
        assertThat(coder.codesOf("0000001", "0000002", areas()))
                .containsEntry("merchant", "XXX")
                .containsEntry("customer", "XXX");
    }

    @Test
    void Haiku를_도구_없이_구조화_출력으로_부른다() throws Exception {
        ClaudeCredentialRunner credentials = mock(ClaudeCredentialRunner.class);
        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir(any())).thenReturn(Path.of("."));
        when(credentials.run(any(), any(), any(), any(), anyList(), any(), any()))
                .thenReturn(Optional.of(new ClaudeResult(0, false, "success", null,
                        "{\"codes\":{\"merchant\":\"MRC\",\"customer\":\"CUS\"}}")));

        new ClaudeBusinessAreaCoder(credentials, paths)
                .codesOf("0000001", "0000002", areas());

        @SuppressWarnings("unchecked")
        var arguments = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(credentials).run(any(), any(), any(), any(), arguments.capture(), any(), any());
        assertThat(arguments.getValue())
                .containsSubsequence("--model", "haiku")
                .containsSubsequence("--tools", "")
                .contains("--json-schema")
                .doesNotContain("--effort");
    }

    private Map<String, String> codesFrom(String body) throws Exception {
        return BusinessAreaCodes.of(JSON.readTree(body).path("codes"), areas());
    }

    private Map<String, String> areas() {
        Map<String, String> areas = new LinkedHashMap<>();
        areas.put("merchant", "가맹점");
        areas.put("customer", "고객관리");
        return areas;
    }
}
