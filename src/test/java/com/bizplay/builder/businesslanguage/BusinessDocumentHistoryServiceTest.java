package com.bizplay.builder.businesslanguage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDocumentHistoryServiceTest {

    private final BusinessDocumentHistoryService service =
            new BusinessDocumentHistoryService(new BusinessLanguageMarkdown());

    @Test
    void 정책서는_항목별_추가_수정_삭제를_구분한다() {
        String before = """
                ## 회원 가입
                만 14세 이상만 가입할 수 있다.

                ## 탈퇴
                즉시 탈퇴한다.
                """;
        String after = """
                ## 회원 가입
                만 15세 이상만 가입할 수 있다.

                ## 휴면
                1년 동안 이용하지 않으면 휴면 처리한다.
                """;

        assertThat(service.changes(BusinessDocumentKind.POLICY, before, after))
                .extracting(BusinessDocumentChange::title, BusinessDocumentChange::type)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("회원 가입", BusinessDocumentChangeType.MODIFIED),
                        org.assertj.core.groups.Tuple.tuple("휴면", BusinessDocumentChangeType.ADDED),
                        org.assertj.core.groups.Tuple.tuple("탈퇴", BusinessDocumentChangeType.REMOVED));
    }

    @Test
    void 표준용어는_용어별_추가_수정_삭제를_구분한다() {
        String before = """
                # 표준용어

                | 표준용어 | 용어 정의 | 동의어·유사어 |
                | --- | --- | --- |
                | 회원 | 서비스 이용자 | 사용자 |
                | 판매처 | 상품을 판매하는 조직 | 지점 |
                """;
        String after = """
                # 표준용어

                | 표준용어 | 용어 정의 | 동의어·유사어 |
                | --- | --- | --- |
                | 회원 | 서비스에 가입한 이용자 | 사용자, 고객 |
                | 이용기관 | 서비스를 운영하는 기관 | 발행기관 |
                """;

        assertThat(service.changes(BusinessDocumentKind.STANDARD_TERMS, before, after))
                .extracting(BusinessDocumentChange::title, BusinessDocumentChange::type)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("회원", BusinessDocumentChangeType.MODIFIED),
                        org.assertj.core.groups.Tuple.tuple("이용기관", BusinessDocumentChangeType.ADDED),
                        org.assertj.core.groups.Tuple.tuple("판매처", BusinessDocumentChangeType.REMOVED));
    }
}
