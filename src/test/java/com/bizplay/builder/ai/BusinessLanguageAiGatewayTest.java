package com.bizplay.builder.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLanguageAiGatewayTest {

    @TempDir Path clone;

    @Test
    void 사업언어_입력에_해당하는_실제_근거만_받는다() throws Exception {
        Path domain = Files.createDirectories(clone.resolve("domains/payment"));
        Files.writeString(domain.resolve("approval.md"), "# 승인");
        Path pages = Files.createDirectories(clone.resolve("core/webview/pages"));
        Files.writeString(pages.resolve("card-list.md"), "화면명: 보유 카드 조회");
        Files.writeString(clone.resolve("core/webview/ia.md"), "# 메뉴구조도");
        Files.writeString(clone.resolve("index.json"), "{}");
        Files.writeString(clone.resolve("secret.md"), "비공개");

        assertThat(BusinessLanguageAiGateway.validReference(
                "domains/payment/approval.md#rule-01", clone)).isTrue();
        assertThat(BusinessLanguageAiGateway.validReference(
                "core/webview/pages/card-list.md#화면명세", clone)).isTrue();
        assertThat(BusinessLanguageAiGateway.validReference("core/webview/ia.md", clone)).isTrue();
        assertThat(BusinessLanguageAiGateway.validReference("index.json", clone)).isTrue();
        assertThat(BusinessLanguageAiGateway.validReference("core/webview/pages/card-list.html", clone)).isFalse();
        assertThat(BusinessLanguageAiGateway.validReference("secret.md", clone)).isFalse();
        assertThat(BusinessLanguageAiGateway.validReference("domains/../secret.md", clone)).isFalse();
        assertThat(BusinessLanguageAiGateway.validReference("domains/payment/missing.md", clone)).isFalse();
    }

    @Test
    void 표준용어_지시는_업무용어와_화면표현을_함께_수집한다() {
        assertThat(BusinessLanguageAiGateway.instruction())
                .contains("업무 용어", "화면 항목명", "상태명", "주요 행동명")
                .contains("완성된 안내·오류 문장", "일회성 화면 문구")
                .contains("domains", "화면 명세", "IA")
                .contains("표준용어 | 용어 정의 | 동의어·유사어")
                .contains("DB 표·열 이름, 영문 변수명, 코드값")
                .contains("화면 명세를 하나씩 순차 열람하지 않는다", "Grep");
    }

    @Test
    void 정책서는_개발_구현이_아니라_업무_판단_기준만_작성한다() {
        assertThat(BusinessLanguageAiGateway.instruction())
                .contains("업무 담당자가 판단하고 적용할 수 있는 기준")
                .contains("한 도메인에만 있는 정책도", "대표 사례만 남기지 않는다")
                .contains("API 경로", "HTTP 방식", "컨트롤러", "DB 표·열", "소스 경로")
                .contains("구현 설명은 쓰지 않고 그로부터 확인되는 업무 규칙만")
                .contains("버튼·입력 항목의 단순 동작 설명은 정책으로 만들지 않는다");
    }

    @Test
    void Builder가_모든_도메인과_화면_입력을_근거_목록으로_만든다() throws Exception {
        Files.writeString(clone.resolve("index.json"), "{}");
        Files.createDirectories(clone.resolve("domains/payment"));
        Files.writeString(clone.resolve("domains/payment/approval.md"), "# 승인");
        Files.writeString(clone.resolve("domains/payment/cancel.md"), "# 취소");
        Files.createDirectories(clone.resolve("core/backoffice/pages"));
        Files.writeString(clone.resolve("core/backoffice/ia.md"), "# IA");
        Files.writeString(clone.resolve("core/backoffice/pages/list.md"), "화면명: 목록");
        Files.createDirectories(clone.resolve("core/webview/pages"));
        Files.writeString(clone.resolve("core/webview/ia.md"), "# IA");
        Files.writeString(clone.resolve("core/webview/pages/home.md"), "화면명: 홈");

        assertThat(BusinessLanguageAiGateway.sourceInventory(clone))
                .containsExactlyInAnyOrder(
                        "domains/payment/approval.md", "domains/payment/cancel.md",
                        "core/backoffice/ia.md", "core/backoffice/pages/list.md",
                        "core/webview/ia.md", "core/webview/pages/home.md", "index.json");
    }

    @Test
    void 전수_검토와_근거_기록을_명시한다() {
        assertThat(BusinessLanguageAiGateway.instruction())
                .contains("파일 끝까지 나누어 읽는다")
                .contains("파일별 작업 목록을 모두 만든 뒤")
                .contains("POLICY_INCLUDED", "NO_BUSINESS_POLICY", "domainCoverage")
                .contains("모든 도메인 파일을 sourceRefs에 기록한다")
                .contains("각 시스템의 IA를 모두 Read")
                .contains("시스템마다 화면 명세 근거를 한 개 이상")
                .contains("index.json을 Read하고 sourceRefs에 기록한다");
    }

    @Test
    void 실제_저장소_파일을_필수_검토_체크리스트로_제공한다() throws Exception {
        Files.createDirectories(clone.resolve("domains/payment"));
        Files.writeString(clone.resolve("domains/payment/approval.md"), "# 승인");
        Files.createDirectories(clone.resolve("core/webview/pages"));
        Files.writeString(clone.resolve("core/webview/ia.md"), "# IA");
        Files.writeString(clone.resolve("core/webview/pages/home.md"), "화면명: 홈");

        assertThat(BusinessLanguageAiGateway.instruction(clone))
                .contains("필수 검토 체크리스트")
                .contains("domains/payment/approval.md")
                .contains("core/webview/ia.md")
                .contains("  - webview");
    }

    @Test
    void 빈_문서와_빈_근거는_출력_스키마에서_거절한다() {
        assertThat(BusinessLanguageAiGateway.OUTPUT_SCHEMA)
                .contains("\"policyMarkdown\":{\"type\":\"string\",\"minLength\":100}")
                .contains("\"standardTermsMarkdown\":{\"type\":\"string\",\"minLength\":100}")
                .contains("\"sourceRefs\":{\"type\":\"array\",\"minItems\":1")
                .contains("\"domainCoverage\":{\"type\":\"array\",\"minItems\":1")
                .contains("POLICY_INCLUDED", "NO_BUSINESS_POLICY");
    }

    @Test
    void 전수_검토_초안은_최소_이십분의_실행_시간을_보장한다() {
        assertThat(BusinessLanguageAiGateway.businessLanguageTimeout(Duration.ofMinutes(10)))
                .isEqualTo(Duration.ofMinutes(20));
        assertThat(BusinessLanguageAiGateway.businessLanguageTimeout(Duration.ofMinutes(30)))
                .isEqualTo(Duration.ofMinutes(30));
    }
}
