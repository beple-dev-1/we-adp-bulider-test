package com.bizplay.builder.devrequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 꾸러미의 계약서 본문 {@code dev-request.md} 를 절 열로 뽑나.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}
 * 「{@code dev-request.md} 에 무엇이 들어가나」.
 *
 * <p>⭐ <b>4절이 계약의 심장이다</b> — 다툼은 「했다/안 했다」보다 「이건 범위였다/아니었다」에서 난다.
 * 그래서 화면(`dev-request.html`)의 배치를 그대로 옮기지 않고 <b>개발 범위 바로 뒤 큰 절</b>로 둔다.
 */
class DevRequestDocumentTest {

    @TempDir Path dir;

    private final DevRequestDocument documents = new DevRequestDocument();

    @Test
    void 절_열을_순서대로_낸다() throws IOException {
        String md = documents.render(meta(), content(), manifest());

        assertThat(md).contains("# DR-009");
        assertThat(md.indexOf("## 1. 요청 내용"))
                .isLessThan(md.indexOf("## 2. 요구사항 전체"));
        assertThat(md.indexOf("## 3. 개발 범위"))
                .isLessThan(md.indexOf("## 4. 제외 범위"));
        assertThat(md).contains("## 5. 완료 조건", "## 6. 정한 것", "## 7. 화면 외 구현",
                "## 8. 화면별 산출물 목록", "## 9. 전송 정보", "## 10. 첨부 목록",
                "## 11. 돌려받을 것");
        assertThat(md.indexOf("## 10. 첨부 목록")).isLessThan(md.indexOf("## 11. 돌려받을 것"));
    }

    /**
     * ⭐ <b>11절은 두 계약 파일을 따르라는 안내만 둔다</b> — 설계(꾸러미 설계 「돌려받을 것」)가 그렇게 정했다.
     * 개발이 먼저 여는 문서에서 돌려보낼 길을 알아야 한다. ⛔ 돌려보내는 법을 여기에 다시 적지 않는다 —
     * 그 값은 expected-back.md 에 있고 두 곳에 적으면 갈린다.
     */
    @Test
    void 열한째_절은_두_계약_파일을_따르라고만_안내한다() throws IOException {
        String md = documents.render(meta(), content(), manifest());
        String section = md.substring(md.indexOf("## 11. 돌려받을 것"));

        assertThat(section).contains("expected-back.md").contains("manifest.json")
                .doesNotContain("return.json");
    }

    /**
     * ⚠ 화면도 화면 외 구현도 없는 요청서가 「화면 외 구현만 담습니다」라고 말하면 7절과 어긋난다
     * (2026-09-24 DR-012 에서 본 모순).
     */
    @Test
    void 화면이_없으면_화면_외_구현이_있다고_말하지_않는다() throws IOException {
        Path empty = dir.resolve("empty-srt.json");
        Files.writeString(empty, """
                {"specVersion": 2, "request": {"label": "DR-012"}, "screens": [], "expectedBack": {"screens": []}}
                """, StandardCharsets.UTF_8);

        String md = documents.render(meta(), content(), empty);
        String section = md.substring(md.indexOf("## 8. 화면별 산출물 목록"), md.indexOf("## 9. 전송 정보"));

        assertThat(section).contains("화면 변경이 없습니다").doesNotContain("화면 외 구현만");
    }

    /**
     * ⚠ <b>절 제목만 보면 안 된다 — 속이 찼는지 본다.</b> 2026-09-22 에 이 시험이 제목만 보는
     * 동안 5절이 빈 채로 통과했다. 재료의 열쇠가 {@code ACCEPTANCE_CRITERION} 인데 시험이
     * {@code ACCEPTANCE} 로 적어 놓고도 초록이었다.
     */
    @Test
    void 완료_조건과_정한_것은_제목만_아니라_속이_찬다() throws IOException {
        String md = documents.render(meta(), content(), manifest());
        String acceptance = md.substring(md.indexOf("## 5. 완료 조건"), md.indexOf("## 6. 정한 것"));
        String decided = md.substring(md.indexOf("## 6. 정한 것"), md.indexOf("## 7. 화면 외 구현"));

        assertThat(acceptance).contains("프리필된 값으로 가입이 끝난다");
        // ⭐ 개발은 「질문 → 답」을 받는다 — 권장안으로 정한 것은 그렇다고 적는다 (2026-09-24 사용자 확정).
        assertThat(decided)
                .contains("- 생년월일이 없는 회원은 어떻게 하나 → 빈칸으로 두고 가입을 막지 않는다")
                .contains("- 동의 문구를 바꿀지 → 바꾸지 않는다 (AI 권장안)")
                .contains("확인 필요: 옛 결과의 확인 필요");
    }

    @Test
    void 화면_외_구현은_갈래로_묶고_판정_방법까지_싣는다() throws IOException {
        String md = documents.render(meta(), content(), manifest());
        String section = md.substring(md.indexOf("## 7. 화면 외 구현"),
                md.indexOf("## 8. 화면별 산출물 목록"));

        assertThat(section).contains("### API 변경")
                .contains("domains/join/prefill.md")
                .contains("판정 방법: 응답 스키마에 필드가 있는지 본다");
    }

    @Test
    void 표지에_요청_이름과_시스템과_적용_대상과_담당자를_적는다() throws IOException {
        String md = documents.render(meta(), content(), manifest());

        assertThat(md).contains("에이블리 회원가입 프리필")
                .contains("EXW").contains("에이블리")
                .contains("FRD-002").contains("이영희");
    }

    /** ⭐ 제외 범위는 운영 반영과 범위 밖을 <b>갈라서</b> 보이고 까닭(note)을 함께 싣는다. */
    @Test
    void 제외_범위는_운영_반영과_범위_밖을_갈라_까닭까지_싣는다() throws IOException {
        String md = documents.render(meta(), content(), manifest());
        String section = md.substring(md.indexOf("## 4. 제외 범위"), md.indexOf("## 5. 완료 조건"));

        assertThat(section).contains("운영 반영").contains("배너 문구를 운영자가 바꾼다")
                .contains("이미 기능이 있다")
                .contains("범위 밖").contains("제휴사 앱은 이 저장소 밖이다");
        assertThat(section).doesNotContain("체크디지트");   // 개발 범위 항목은 여기 없다
    }

    /**
     * ⛔ <b>8절은 손으로 적지 않는다</b> — {@code manifest.json} 에서 만든다.
     * 손으로 적으면 {@code screens/} 폴더와 갈리고, 갈린 순간 어느 쪽이 맞는지 아무도 모른다.
     */
    @Test
    void 화면별_산출물_목록은_manifest_에서_만들고_파일마다_무엇인지_적는다() throws IOException {
        String md = documents.render(meta(), content(), manifest());
        String section = md.substring(md.indexOf("## 8. 화면별 산출물 목록"),
                md.indexOf("## 9. 전송 정보"));

        assertThat(section).contains("screens/EXW/EXW-UWV-70-30-10-C/as-is.html")
                .contains("바뀌기 전 화면")
                .contains("screens/EXW/EXW-UWV-70-30-10-C/to-be.md")
                .contains("바뀐 뒤 기능정의서");
    }

    /** ⚠ 화면 0장이 정상이다 — 백엔드만인 FRD 는 워크트리 커밋도 없다. */
    @Test
    void 화면이_없으면_8절이_없다고_적고_절을_비우지_않는다() throws IOException {
        Path empty = dir.resolve("empty.json");
        Files.writeString(empty, """
                {"specVersion": 2, "request": {"label": "DR-011"}, "screens": [], "expectedBack": {"screens": []}}
                """, StandardCharsets.UTF_8);

        String md = documents.render(meta(), content(), empty);
        String section = md.substring(md.indexOf("## 8. 화면별 산출물 목록"),
                md.indexOf("## 9. 전송 정보"));

        assertThat(section).contains("화면 변경이 없습니다");
    }

    private DevRequestDocument.Meta meta() {
        return new DevRequestDocument.Meta("DR-009", "에이블리 회원가입 프리필", "EXW",
                List.of("에이블리"), "FRD-002", "이영희",
                LocalDate.of(2026, 9, 22), LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20),
                "웹뷰 진입 파라미터는 기존 규약을 그대로 씁니다.", "화면흐름.pdf", 248_000L);
    }

    private DevelopmentRequestContent content() {
        return new DevelopmentRequestContent(
                "에이블리 회원가입에서 회원정보를 미리 채운다.",
                "약관 동의는 그대로 둔다고 확인했다.",
                List.of(
                        new DevelopmentRequestContent.Requirement(1, "체크디지트를 재계산해 대조한다",
                                "DEVELOP", "개발", null),
                        new DevelopmentRequestContent.Requirement(2, "배너 문구를 운영자가 바꾼다",
                                "OPERATE", "운영 반영", "이미 기능이 있다"),
                        new DevelopmentRequestContent.Requirement(3, "제휴사 앱 화면을 고친다",
                                "OUTSIDE", "범위 밖", "제휴사 앱은 이 저장소 밖이다")),
                List.of(),
                List.of(new DevelopmentRequestContent.BackendChange("API", "API 변경",
                        "domains/join/prefill.md", "회원정보 조회 응답에 생년월일을 더한다",
                        1, "민원 2026-09-14", "응답 스키마에 필드가 있는지 본다", true)),
                List.of(new DevelopmentRequestContent.Note("ACCEPTANCE_CRITERION", "프리필된 값으로 가입이 끝난다"),
                        new DevelopmentRequestContent.Note("DECISION_INTERVIEW",
                                "생년월일이 없는 회원은 어떻게 하나\n빈칸으로 두고 가입을 막지 않는다"),
                        new DevelopmentRequestContent.Note("DECISION_RECOMMENDED", "동의 문구를 바꿀지\n바꾸지 않는다"),
                        new DevelopmentRequestContent.Note("OPEN_ISSUE", "옛 결과의 확인 필요")));
    }

    private Path manifest() throws IOException {
        Path path = dir.resolve("manifest.json");
        Files.writeString(path, """
                {
                  "specVersion": 2,
                  "request": {"label": "DR-009", "asIsCommit": "aaa", "toBeCommit": "bbb"},
                  "screens": [
                    {
                      "systemCode": "EXW",
                      "screenId": "EXW-UWV-70-30-10-C",
                      "displayName": "에이블리 회원가입",
                      "newScreen": false,
                      "files": [
                        {"path": "screens/EXW/EXW-UWV-70-30-10-C/as-is.html", "kind": "as-is-html", "sha256": "a"},
                        {"path": "screens/EXW/EXW-UWV-70-30-10-C/to-be.md", "kind": "to-be-md", "sha256": "b"}
                      ]
                    }
                  ],
                  "expectedBack": {"screens": ["EXW-UWV-70-30-10-C"]}
                }
                """, StandardCharsets.UTF_8);
        return path;
    }
}
