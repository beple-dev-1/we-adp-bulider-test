package com.bizplay.builder.businesslanguage;

import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ExtendedModelMap;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessLanguageControllerTest {

    @Mock private BusinessDocumentService documents;
    @Mock private BusinessDocumentSeedService seeds;
    @Mock private AccountMapper accounts;

    @Test
    void 정책서를_마크다운_파일로_다운로드한다() {
        String markdown = "## 1. 회원 가입 기준\n- 만 14세 이상만 가입할 수 있다.";
        BusinessDocument policy = new BusinessDocument("0000001", BusinessDocumentKind.POLICY,
                markdown, "[]", Instant.parse("2026-09-01T11:15:00Z"), "0000001");
        when(documents.find("0000001", BusinessDocumentKind.POLICY)).thenReturn(Optional.of(policy));
        BusinessLanguageController controller = new BusinessLanguageController(documents, seeds, accounts);

        var response = controller.downloadPolicy("0000001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/markdown;charset=UTF-8");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("2026-09-01").contains(".md");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(markdown);
    }

    @Test
    void 정책서와_표준용어의_마지막_수정자_이름을_화면에_전달한다() {
        BusinessDocument policy = new BusinessDocument("0000001", BusinessDocumentKind.POLICY,
                "## 정책", "[]", Instant.parse("2026-09-01T11:15:00Z"), "0000002");
        BusinessDocument terms = new BusinessDocument("0000001", BusinessDocumentKind.STANDARD_TERMS,
                "| 표준용어 | 뜻 | 달리 부르는 말 | 사용하지 않을 표현 |", "[]",
                Instant.parse("2026-09-01T11:15:00Z"), "0000002");
        Account editor = mock(Account.class);
        when(editor.getName()).thenReturn("홍길동");
        when(documents.find("0000001", BusinessDocumentKind.POLICY)).thenReturn(Optional.of(policy));
        when(documents.find("0000001", BusinessDocumentKind.STANDARD_TERMS)).thenReturn(Optional.of(terms));
        when(documents.seed("0000001")).thenReturn(Optional.empty());
        when(accounts.selectById("0000002")).thenReturn(Optional.of(editor));
        BusinessLanguageController controller = new BusinessLanguageController(documents, seeds, accounts);
        ExtendedModelMap model = new ExtendedModelMap();

        controller.document("0000001", "policy", false, null, false, model);

        assertThat(model.get("policyUpdatedBy")).isEqualTo("홍길동");
        assertThat(model.get("termsUpdatedBy")).isEqualTo("홍길동");
    }

    @Test
    void 정책서_수정이력과_선택한_개정본의_변경내용을_화면에_전달한다() {
        BusinessDocumentRevision second = new BusinessDocumentRevision(
                "0000001", BusinessDocumentKind.POLICY, 2, "## 가입\n변경", "[]",
                BusinessDocumentRevisionType.EDIT, Instant.parse("2026-09-01T12:00:00Z"), "0000002");
        BusinessDocumentRevision first = new BusinessDocumentRevision(
                "0000001", BusinessDocumentKind.POLICY, 1, "## 가입\n기존", "[]",
                BusinessDocumentRevisionType.INITIAL_DRAFT, Instant.parse("2026-09-01T11:00:00Z"), "0000001");
        Account editor = mock(Account.class);
        when(editor.getName()).thenReturn("홍길동");
        when(documents.revisions("0000001", BusinessDocumentKind.POLICY)).thenReturn(List.of(second, first));
        when(documents.changes(BusinessDocumentKind.POLICY, first.content(), second.content()))
                .thenReturn(List.of(new BusinessDocumentChange(
                        BusinessDocumentChangeType.MODIFIED, "가입", "기존", "변경")));
        when(accounts.selectById("0000002")).thenReturn(Optional.of(editor));
        when(accounts.selectById("0000001")).thenReturn(Optional.empty());
        BusinessLanguageController controller = new BusinessLanguageController(documents, seeds, accounts);
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.history("0000001", "policy", null, model);

        assertThat(view).isEqualTo("artifacts/business-language-history");
        assertThat(model.get("documentLabel")).isEqualTo("정책서");
        assertThat((List<?>) model.get("revisions")).hasSize(2);
        assertThat(((BusinessLanguageController.RevisionView) model.get("selectedRevision")).editorName())
                .isEqualTo("홍길동");
        assertThat((List<?>) model.get("changes")).hasSize(1);
    }
}
