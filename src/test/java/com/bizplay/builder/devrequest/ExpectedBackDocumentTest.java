package com.bizplay.builder.devrequest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「돌려받을 것」 계약서 {@code expected-back.md} 를 제대로 뽑나.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-dev-feedback-design.md} 「⛔ ② 는 파일
 * 하나씩 받지 않는다 — 배치로 받는다」 · {@code 2026-08-22-dev-request-package-design.md}
 * 「돌려받을 대상만 넣는다」.
 *
 * <p>⭐ <b>목록이 아니라 표다.</b> 대상마다 <b>어느 구성요소가 필수인가</b>를 적고, 개발은 필수마다
 * {@code changed} 또는 {@code unchanged} 를 <b>정확히 하나</b> 채운다. 그래야 「누락」과
 * 「봤는데 안 바뀜」이 갈린다.
 *
 * <p>⭐ <b>테스트 시나리오는 우리가 먼저 적는다.</b> 개발은 실제 결과·판정·근거만 채운다 —
 * 빈 양식으로 보내면 돌아오는 것이 기계가 못 읽는 모양이 된다(2026-08-27 확정).
 */
class ExpectedBackDocumentTest {

    private final ExpectedBackDocument documents = new ExpectedBackDocument();

    @Test
    void 머리에_요청_이름과_기준_커밋과_돌려보낼_자리를_적는다() {
        String md = documents.render(meta(), back(List.of()), content());

        assertThat(md).contains("DR-009")
                .contains("370cd63e")
                .contains("feedback/DR-009");
    }

    /** ⭐ 화면마다 필수 구성요소와 채울 칸을 함께 낸다. */
    @Test
    void 화면_회신_대상을_표로_내고_구성요소마다_채울_칸을_둔다() {
        String md = documents.render(meta(), back(List.of()), content());
        String section = md.substring(md.indexOf("## 1. 화면"), md.indexOf("## 2."));

        assertThat(section).contains("EXW-UWV-70-30-10-C")
                .contains("pages").contains("screen-md").contains("index")
                .contains("changed").contains("unchanged");
    }

    /**
     * ⚠ <b>보호 화면은 화면 md 를 안 받는다</b>(설계). 그 화면에서 필수를 빼지 않으면
     * <b>배치가 영원히 안 찬다</b> — 개발이 채울 수 없는 칸을 기다리게 된다.
     */
    @Test
    void 보호_화면은_화면_md_를_필수에서_뺀다() {
        String md = documents.render(meta(), back(List.of("EXW-UWV-70-30-10-C")), content());
        String row = lineWith(md, "EXW-UWV-70-30-10-C");

        assertThat(row).contains("pages").contains("index");
        assertThat(row).doesNotContain("screen-md");
        assertThat(md).contains("사람이 손댄 화면");
    }

    /** ⭐ 백엔드 대상은 경로 꼴이 아니어도 목록에서 사라지지 않는다 (설계 2026-08-25 교정). */
    @Test
    void 백엔드_대상은_경로_꼴이_아니어도_목록에_남는다() {
        String md = documents.render(meta(), back(List.of()), content());
        String section = md.substring(md.indexOf("## 2."), md.indexOf("## 3."));

        assertThat(section).contains("domains/join/prefill.md")
                .contains("배치 서버 설정")   // 경로 꼴이 아닌 대상
                .contains("회원정보 조회 응답에 생년월일을 더한다");
    }

    /** ⭐ 우리가 먼저 적은 TC 가 실리고, 개발이 채울 칸이 비어 있다. */
    @Test
    void 테스트는_TC_를_먼저_적고_개발이_채울_칸을_남긴다() {
        String md = documents.render(meta(), back(List.of()), content());

        assertThat(md).contains("## 3. 단위테스트").contains("TC-001")
                .contains("생년월일이 없으면 빈 값으로 응답한다");
        assertThat(md).contains("## 4. 통합테스트").contains("TC-101")
                .contains("완료 조건").contains("실제 결과").contains("판정").contains("근거");
    }

    /** ⚠ 시나리오가 없어도 계약은 성립한다 — 빈 양식이 그대로 나간다(설계). */
    @Test
    void 시나리오가_없어도_양식은_나간다() {
        DevelopmentRequestContent bare = new DevelopmentRequestContent(
                "요청 원문", null, List.of(), List.of(), List.of(), List.of());

        String md = documents.render(meta(), ExpectedBack.of("feedback/DR-009", "370cd63e", bare, List.of()), bare);

        assertThat(md).contains("## 3. 단위테스트").contains("## 4. 통합테스트")
                .contains("아직 시나리오가 없습니다");
    }

    private static String lineWith(String md, String needle) {
        return md.lines().filter(line -> line.contains(needle)).findFirst().orElse("");
    }

    private ExpectedBackDocument.Meta meta() {
        return new ExpectedBackDocument.Meta("DR-009");
    }

    /** ⛔ 「무엇이 필수인가」는 여기서 짓지 않는다 — ExpectedBack 이 계산한 것을 그대로 쓴다. */
    private ExpectedBack back(List<String> protectedScreens) {
        return ExpectedBack.of("feedback/DR-009", "370cd63e098d2984cb88ef2451ce7d7a8e467000",
                content(), protectedScreens);
    }

    private DevelopmentRequestContent content() {
        return new DevelopmentRequestContent(
                "에이블리 회원가입에서 회원정보를 미리 채운다.", null,
                List.of(new DevelopmentRequestContent.Requirement(1, "프리필한다", "DEVELOP", "개발", null)),
                List.of(new DevelopmentRequestContent.Screen("0000002", "EXW-UWV-70-30-10-C",
                        "에이블리 회원가입", "EXW", null, List.of("회원정보를 미리 채운다"),
                        List.of(), List.of())),
                List.of(new DevelopmentRequestContent.BackendChange("API", "API 변경",
                                "domains/join/prefill.md", "회원정보 조회 응답에 생년월일을 더한다",
                                1, "민원", "응답 스키마를 본다", true),
                        new DevelopmentRequestContent.BackendChange("BATCH", "배치 변경",
                                "배치 서버 설정", "야간 배치 주기를 바꾼다",
                                1, "운영 요청", "설정값을 본다", true)),
                List.of(new DevelopmentRequestContent.Note("ACCEPTANCE_CRITERION",
                        "프리필된 값으로 가입이 끝난다")))
                .withTestScenarios(List.of(
                        new DevelopmentRequestContent.TestScenario("UNIT", 1, "TC-001",
                                "생년월일이 없으면 빈 값으로 응답한다", "없음", "회원에 생년월일이 없음(mock)",
                                "조회 API 를 부른다", "생년월일이 빈 값으로 온다"),
                        new DevelopmentRequestContent.TestScenario("INTEGRATION", 1, "TC-101",
                                "프리필된 값으로 가입이 끝난다", "TC-001", "회원정보가 있는 계정",
                                "웹뷰로 회원가입에 들어간다", "이름과 생년월일이 미리 채워진다")));
    }
}
