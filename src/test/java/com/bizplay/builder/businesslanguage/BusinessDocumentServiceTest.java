package com.bizplay.builder.businesslanguage;

import com.bizplay.builder.project.ProjectPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessDocumentServiceTest {

    @Mock private BusinessDocumentMapper documents;
    @Mock private BusinessDocumentRevisionMapper revisions;
    @Mock private BusinessDocumentSeedMapper seeds;
    @Mock private BusinessLanguageMarkdown markdown;
    @Mock private BusinessDocumentHistoryService history;
    @Mock private ProjectPaths paths;

    @Test
    void 정책서를_저장하면_최신본과_새_개정본을_함께_저장한다() {
        BusinessDocument current = document(BusinessDocumentKind.POLICY, "## 가입\n기존", "[\"domains/account.md\"]");
        when(markdown.normalizePolicy("수정 입력")).thenReturn("## 가입\n변경\n");
        when(documents.selectOneForUpdate("0000001", BusinessDocumentKind.POLICY))
                .thenReturn(Optional.of(current));
        when(revisions.nextRevisionNo("0000001", BusinessDocumentKind.POLICY)).thenReturn(2);
        when(documents.updateContent("0000001", BusinessDocumentKind.POLICY,
                "## 가입\n변경\n", "0000002")).thenReturn(1);

        service().savePolicy("0000001", "수정 입력", "0000002");

        verify(revisions).insert("0000001", BusinessDocumentKind.POLICY, 2,
                "## 가입\n변경\n", "[\"domains/account.md\"]",
                BusinessDocumentRevisionType.EDIT, "0000002");
    }

    @Test
    void 과거_개정본을_복원하면_새로운_복원_개정본으로_남긴다() {
        BusinessDocument current = document(BusinessDocumentKind.POLICY, "## 가입\n현재", "[\"current\"]");
        BusinessDocumentRevision old = new BusinessDocumentRevision(
                "0000001", BusinessDocumentKind.POLICY, 1, "## 가입\n과거", "[\"old\"]",
                BusinessDocumentRevisionType.INITIAL_DRAFT, Instant.EPOCH, "0000001");
        when(documents.selectOneForUpdate("0000001", BusinessDocumentKind.POLICY))
                .thenReturn(Optional.of(current));
        when(revisions.selectOne("0000001", BusinessDocumentKind.POLICY, 1)).thenReturn(Optional.of(old));
        when(revisions.nextRevisionNo("0000001", BusinessDocumentKind.POLICY)).thenReturn(3);
        when(documents.updateDocument("0000001", BusinessDocumentKind.POLICY,
                old.content(), old.sourceRefs(), "0000002")).thenReturn(1);

        service().restore("0000001", BusinessDocumentKind.POLICY, 1, "0000002");

        verify(revisions).insert("0000001", BusinessDocumentKind.POLICY, 3,
                old.content(), old.sourceRefs(), BusinessDocumentRevisionType.RESTORE, "0000002");
    }

    @Test
    void 선택한_표준용어_한_행만_수정하고_새_개정본을_남긴다() {
        BusinessDocument current = document(BusinessDocumentKind.STANDARD_TERMS, "기존 문서", "[]");
        StandardTerm first = new StandardTerm("회원", "기존 정의", "", "");
        StandardTerm second = new StandardTerm("판매처", "판매하는 곳", "가맹점", "");
        StandardTerm changed = new StandardTerm("회원", "변경한 정의", "가입자", "");
        when(documents.selectOneForUpdate("0000001", BusinessDocumentKind.STANDARD_TERMS))
                .thenReturn(Optional.of(current));
        when(markdown.terms("기존 문서")).thenReturn(List.of(first, second));
        when(markdown.termsMarkdown(List.of(changed, second))).thenReturn("변경 문서");
        when(revisions.nextRevisionNo("0000001", BusinessDocumentKind.STANDARD_TERMS)).thenReturn(2);
        when(documents.updateContent("0000001", BusinessDocumentKind.STANDARD_TERMS,
                "변경 문서", "0000002")).thenReturn(1);

        service().updateTerm("0000001", 0, changed, "0000002");

        verify(revisions).insert("0000001", BusinessDocumentKind.STANDARD_TERMS, 2,
                "변경 문서", "[]", BusinessDocumentRevisionType.EDIT, "0000002");
    }

    @Test
    void 표준용어의_마지막_수정정보는_그_행이_바뀐_개정에서_가져온다() {
        StandardTerm memberBefore = new StandardTerm("회원", "기존 정의", "", "");
        StandardTerm memberAfter = new StandardTerm("회원", "변경한 정의", "가입자", "");
        StandardTerm officeBefore = new StandardTerm("판매처", "기존", "", "");
        StandardTerm officeAfter = new StandardTerm("판매처", "변경", "", "");
        BusinessDocument current = document(BusinessDocumentKind.STANDARD_TERMS, "3차", "[]");
        Instant firstAt = Instant.parse("2026-09-01T09:00:00Z");
        Instant secondAt = Instant.parse("2026-09-01T10:00:00Z");
        Instant thirdAt = Instant.parse("2026-09-01T11:00:00Z");
        List<BusinessDocumentRevision> history = List.of(
                revision(3, "3차", thirdAt, "0000003"),
                revision(2, "2차", secondAt, "0000002"),
                revision(1, "1차", firstAt, "0000001"));
        when(revisions.selectAll("0000001", BusinessDocumentKind.STANDARD_TERMS)).thenReturn(history);
        when(markdown.terms("3차")).thenReturn(List.of(memberAfter, officeAfter));
        when(markdown.terms("2차")).thenReturn(List.of(memberAfter, officeBefore));
        when(markdown.terms("1차")).thenReturn(List.of(memberBefore, officeBefore));

        List<StandardTermAudit> audits = service().termAudits("0000001", current);

        assertThat(audits).extracting(StandardTermAudit::updatedAt)
                .containsExactly(secondAt, thirdAt);
        assertThat(audits).extracting(StandardTermAudit::updatedBy)
                .containsExactly("0000002", "0000003");
    }

    private BusinessDocumentService service() {
        return new BusinessDocumentService(documents, revisions, seeds, markdown, history, paths);
    }

    private static BusinessDocument document(BusinessDocumentKind kind, String content, String refs) {
        return new BusinessDocument("0000001", kind, content, refs, Instant.EPOCH, "0000001");
    }

    private static BusinessDocumentRevision revision(int revisionNo, String content, Instant createdAt,
                                                       String createdBy) {
        return new BusinessDocumentRevision("0000001", BusinessDocumentKind.STANDARD_TERMS,
                revisionNo, content, "[]", BusinessDocumentRevisionType.EDIT, createdAt, createdBy);
    }
}
