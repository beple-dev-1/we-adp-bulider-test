package com.bizplay.builder.devrequest;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;

/**
 * {@code dev-request.md} 를 찍는다 — 절 열하나다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}.
 *
 * <p><b>1절은 요청 원문과 승인된 인터뷰 요약을 구분해 싣는다.</b> 원문은 사람이 처음 입력한 근거이고,
 * 인터뷰 요약은 분석 결과 승인 전에 확인한 해석이다. 둘을 합쳐 다시 쓰지 않는다.
 *
 * <p>⛔ <b>화면별 변경 내용을 여기 펼치지 마라.</b> 상세의 정본은 화면 폴더의 {@code changes.md}
 * 이고 여기 8절은 <b>목록만</b>이다.
 *
 * <p>⛔ <b>「공통 정책과 예외」 절을 새로 만들지 마라.</b> 권한·정책은 7절 화면 외 구현의
 * <b>갈래</b>로 산다 — 적을 자리가 둘이면 기획자가 어디에 적었는지에 따라 계약서에서 사라진다.
 */
@Component
public class DevRequestDocumentWriter {

    /** 7절 소제목 순서. ⚠ 「권한」이 여기 있는 것이 「공통 정책과 예외」를 대신하는 자리다. */
    private static final List<String> CATEGORY_ORDER =
            List.of("API", "DATA", "PERMISSION", "BATCH", "NOTIFICATION", "OTHER");

    public String write(DevelopmentRequestService.View view, String deliveryKey, String sentAt,
                        String previousLabel, List<DevRequestPackage.Entry> entries,
                        DevRequestExpectedBack expectedBack) {
        DevelopmentRequest request = view.request();
        DevelopmentRequestContent content = view.content();
        StringBuilder out = new StringBuilder();

        // ── 표지 ──
        out.append("# ").append(request.label()).append(' ').append(request.title()).append("\n\n");
        out.append("| | |\n|---|---|\n");
        out.append("| 개발요청서 번호 | ").append(request.label()).append(" |\n");
        out.append("| 원본 작업 | ").append(view.sourceLabel()).append(" |\n");
        out.append("| 시스템 | ").append(value(request.systemCode())).append(" |\n");
        out.append("| 적용 대상 | ")
                .append(request.facetList().isEmpty() ? "—" : String.join(", ", request.facetList()))
                .append(" |\n");
        out.append("| 요청 담당 | ").append(view.ownerLabel()).append(" |\n");
        out.append("| 전송 시각 | ").append(value(sentAt)).append(" |\n");
        // ⚠ 번호는 프로젝트마다 1번부터라 이것만으로 유일하지 않다. 거를 생각이면 전송 키로 거른다.
        out.append("| 전송 키 | `").append(value(deliveryKey)).append("` |\n");
        out.append("| 앞 개발요청서 | ").append(value(previousLabel)).append(" |\n\n");

        // ── 1. 요청 내용 ──
        out.append("## 1. 요청 내용\n\n");
        out.append("### 요청 원문\n\n");
        out.append(content.summary() == null || content.summary().isBlank()
                ? "기록된 요청 원문이 없습니다.\n" : content.summary().strip() + "\n");
        out.append("\n### 인터뷰에서 정리한 요구사항\n\n");
        out.append(content.interviewSummary() == null || content.interviewSummary().isBlank()
                ? "기록된 인터뷰 요구사항 요약이 없습니다.\n"
                : content.interviewSummary().strip() + "\n");

        // ── 2. 요구사항 전체 ──
        out.append("\n## 2. 요구사항 전체\n\n");
        if (content.requirements().isEmpty()) {
            out.append("정리된 요구사항 항목이 없습니다.\n");
        } else {
            out.append("| # | 요구사항 | 성격 | 비고 |\n|---|---|---|---|\n");
            for (var item : content.requirements()) {
                out.append("| ").append(item.seq())
                        .append(" | ").append(cell(item.requirement()))
                        .append(" | ").append(value(item.natureLabel()))
                        .append(" | ").append(cell(item.note())).append(" |\n");
            }
        }

        // ── 3. 개발 범위 ──
        out.append("\n## 3. 개발 범위\n\n");
        appendRequirements(out, content.developmentRequirements(), "개발이 필요한 항목이 없습니다.");

        // ── 4. 제외 범위 ──
        // ⭐ 다툼은 「했다/안 했다」보다 「이건 범위였다/아니었다」에서 난다. 그래서 큰 절이다.
        out.append("\n## 4. 제외 범위\n\n");
        out.append("아래는 이번 개발요청의 범위가 아닙니다.\n");
        out.append("\n### 운영 반영 — 개발 없이 운영자가 처리합니다\n\n");
        appendRequirements(out, content.operationRequirements(), "해당 항목이 없습니다.");
        out.append("\n### 범위 밖 — 이 저장소의 시스템 밖입니다\n\n");
        appendRequirements(out, content.excludedRequirements(), "해당 항목이 없습니다.");

        // ── 5. 완료 조건 ──
        out.append("\n## 5. 완료 조건\n\n");
        if (content.acceptanceCriteria().isEmpty()) {
            out.append("⚠ 기록된 완료 조건이 없습니다.\n");
        } else {
            content.acceptanceCriteria()
                    .forEach(note -> out.append("- ").append(note.content()).append('\n'));
        }

        // ── 6. 확인 필요 ──
        if (!content.openIssues().isEmpty()) {
            out.append("\n## 6. 확인 필요\n\n");
            out.append("아래는 기획에서 확정하지 못한 내용입니다. 착수 전에 함께 정해 주세요.\n\n");
            content.openIssues()
                    .forEach(note -> out.append("- ").append(note.content()).append('\n'));
        }

        // ── 7. 화면 외 구현 ──
        out.append("\n## 7. 화면 외 구현\n\n");
        appendBackend(out, content);

        // ── 8. 화면별 산출물 목록 ──
        out.append("\n## 8. 화면별 산출물 목록\n\n");
        appendInventory(out, content, entries);

        // ── 9. 전송 정보 ──
        out.append("\n## 9. 전송 정보\n\n");
        out.append("| | |\n|---|---|\n");
        out.append("| 개발 완료 요청일 | ").append(date(request.developmentCompletedOn())).append(" |\n");
        out.append("| 운영 배포 요청일 | ").append(date(request.deploymentOn())).append(" |\n\n");
        out.append("### 기획자 전달사항\n\n");
        out.append(request.plannerComment() == null || request.plannerComment().isBlank()
                ? "전달사항이 없습니다.\n" : request.plannerComment().strip() + "\n");

        // ── 10. 첨부 목록 ──
        out.append("\n## 10. 첨부 목록\n\n");
        if (request.attachmentName() == null || request.attachmentName().isBlank()) {
            out.append("첨부파일이 없습니다.\n");
        } else {
            out.append("- `attachments/").append(request.attachmentName()).append("` — ")
                    .append(request.attachmentSize() == null ? "크기 미상"
                            : request.attachmentSize() + " bytes").append('\n');
        }

        // ── 11. 개발 완료 후 반환 ──
        out.append("\n## 11. 개발 완료 후 반환\n\n")
                .append("개발 완료 후 `expected-back.md`를 실제 구현 결과로 작성해 반환한다.\n")
                .append("자동 수신 대상으로 처리할 파일과 항목은 `manifest.json`의 규격과 일치해야 한다. ")
                .append("계획에 없던 변경은 `expected-back.md`의 별도 검토 대상으로 신고한다.\n");
        return out.toString();
    }

    /**
     * 이 DR에서 개발 완료 뒤 돌려줄 대상과 회신 작성 양식을 만든다.
     *
     * <p>사람이 읽고 작성하는 반환 계약은 {@code expected-back.md} 하나만 정본으로 둔다.
     * 개발요청서 11절은 이 파일과 기계 계약인 {@code manifest.json}을 가리키기만 한다.
     */
    public String writeExpectedBack(DevelopmentRequestService.View view, String deliveryKey,
                                    String planningRepoCommit,
                                    DevRequestExpectedBack expectedBack) {
        DevelopmentRequest request = view.request();
        DevelopmentRequestContent content = view.content();
        StringBuilder out = new StringBuilder("# 개발 완료 후 반환할 것\n\n");
        out.append("이 파일은 개발자가 실제 구현 결과를 채워 반환하는 회신서다. ")
                .append("`<...>`로 표시된 값을 작성하고, 변경한 파일과 함께 반환한다.\n\n")
                .append("## 회신 기준\n\n")
                .append("| 항목 | 값 |\n|---|---|\n")
                .append("| 개발요청서 번호 | `").append(request.label()).append("` |\n")
                .append("| 전송 키 | `").append(value(deliveryKey)).append("` |\n")
                .append("| 기준 기획 저장소 커밋 | `").append(value(planningRepoCommit)).append("` |\n")
                .append("| 회신 배치 번호 | `<이 회신을 식별하는 batchId>` |\n")
                .append("| 구현 저장소 | `<저장소 식별자 또는 URL>` |\n")
                .append("| 구현 브랜치 | `<브랜치명>` |\n")
                .append("| 구현 커밋 | `<40자리 SHA>` |\n")
                .append("| 작성자·작성 시각 | `<작성자 / ISO-8601 시각>` |\n\n")
                .append("## 예상 반환 대상\n\n");
        appendExpectedBack(out, content, expectedBack);
        appendReturnForm(out, content, expectedBack);
        return out.toString();
    }

    /**
     * 개발이 끝나면 무엇이 돌아와야 하나 — <b>이 DR 의 값만</b> 적는다 (병주 확정 2026-08-25).
     *
     * <p>⛔ <b>규약(절차·상태코드·배치 규칙)을 이 절에 넣지 마라.</b>
     * 이 단계는 돌려줄 대상만 기록하고 실제 수신과 반영은 후속 단계가 맡는다.
     *
     * <p>⭐ <b>현재 운영 화면 재동기 표는 {@code manifest.json} 의 {@code expectedBack} 과 같은 자료다</b> —
     * 부르는 쪽이 하나를 만들어 둘에 준다. 여기서 다시 만들지 마라.
     *
     * <p>단위테스트·통합테스트는 <b>코드가 아니라 결과 문서</b>로 돌아온다 — 빌더는 개발 소스를 못 읽는다.
     * 그래서 무엇에 대한 결과인지를 여기서 못박는다: 단위테스트는 7절 항목마다, 통합테스트는 5절 항목마다.
     * 기능명세서는 따로 받지 않는다 — 돌아오는 화면 md 가 「실제로 그렇게 됐다」다.
     */
    private void appendExpectedBack(StringBuilder out, DevelopmentRequestContent content,
                                    DevRequestExpectedBack expectedBack) {
        // 1) 현재 운영 화면 재동기 — 기획 저장소 갱신 재료. 1순위다.
        out.append("### 현재 운영 화면 재동기 — 화면 ").append(expectedBack.screens().size())
                .append("장 · 도메인 모듈 ").append(expectedBack.domains().size()).append("건\n\n");
        if (expectedBack.screens().isEmpty()) {
            out.append("대상 화면이 없습니다.\n\n");
        } else {
            out.append("| 시스템 | 화면ID | 필수 구성요소 | 화면 md 받나 |\n|---|---|---|---|\n");
            for (var row : expectedBack.screens()) {
                out.append("| ").append(value(row.systemCode()))
                        .append(" | `").append(row.screenId()).append('`')
                        .append(" | ").append(String.join(" · ", row.requiredComponents()))
                        .append(" | ").append(row.acceptScreenMd() ? "받는다" : "받지 않는다")
                        .append(" |\n");
            }
            out.append('\n');
        }
        if (expectedBack.domains().isEmpty()) {
            out.append("경로가 특정된 도메인 모듈이 없습니다.\n");
        } else {
            out.append("| 도메인 | 모듈 |\n|---|---|\n");
            for (var row : expectedBack.domains()) {
                out.append("| ").append(row.domain()).append(" | ").append(row.module()).append(" |\n");
            }
        }

        out.append("\n### 화면 외 구현 결과 — ").append(expectedBack.backendChanges().size())
                .append("항목\n\n");
        if (expectedBack.backendChanges().isEmpty()) {
            out.append("없음\n");
        } else {
            out.append("항목마다 실제 구현 결과를 적고, 도메인 정의가 바뀌었다면 변경한 ")
                    .append("`domains/<도메인>/<모듈>.md`를 돌려준다. ")
                    .append("도메인 정의 변경이 없으면 `도메인 변경 없음`으로 적는다.\n\n");
            for (var change : expectedBack.backendChanges()) {
                out.append("- **").append(value(change.target())).append("**")
                        .append(" (").append(value(change.category())).append(") — ")
                        .append(value(change.changeDetail())).append('\n');
            }
        }

        // 2) 단위테스트 결과 — 7절 항목마다 1줄. 우리가 보낸 「판정 방법」에 대응한다.
        List<DevelopmentRequestContent.BackendChange> required = content.requiredChanges();
        out.append("\n### 단위테스트 결과 — 화면 외 구현 ").append(required.size()).append("항목\n\n");
        if (required.isEmpty()) {
            out.append("없음\n");
        } else {
            out.append("항목마다 무엇을 어떻게 검증했나와 결과를 한 줄씩 적어 돌려준다.\n\n");
            for (var change : required) {
                out.append("- **").append(value(change.target())).append("** — 판정 방법: ")
                        .append(change.verification() == null || change.verification().isBlank()
                                ? "⚠ 정해지지 않았습니다" : change.verification().strip())
                        .append('\n');
            }
        }

        // 3) 통합테스트 시나리오 — 5절 완료 조건마다. 요구사항 → 완료 조건 → 시나리오 추적표가 여기서 난다.
        List<DevelopmentRequestContent.Note> criteria = content.acceptanceCriteria();
        out.append("\n### 통합테스트 시나리오 — 완료 조건 ").append(criteria.size()).append("건\n\n");
        if (criteria.isEmpty()) {
            out.append("없음\n");
        } else {
            out.append("완료 조건마다 시나리오 · 단계 · 기대 결과 · 결과를 적어 돌려준다.\n\n");
            criteria.forEach(note -> out.append("- ").append(note.content()).append('\n'));
        }

    }

    /**
     * 개발자가 그대로 채워 돌려줄 수 있는 회신 양식.
     *
     * <p>⭐ 화면 외 구현 결과와 도메인 재동기는 다른 값이다. 구현 완료 여부를 먼저 적고,
     * 실제 도메인 정의가 달라졌는지는 {@code changed}/{@code unchanged} 로 별도 선언한다.
     * 그래야 후속 수신 단계가 파일을 놓을지, 변경 없음 이력만 남길지 판정할 수 있다.
     */
    private void appendReturnForm(StringBuilder out, DevelopmentRequestContent content,
                                  DevRequestExpectedBack expectedBack) {
        out.append("\n## 회신 작성 방법\n\n")
                .append("이 파일의 아래 양식을 항목마다 채운다. 요청 문장을 되풀이하지 말고, ")
                .append("**실제로 구현된 최종 상태와 그 근거**를 적는다.\n\n")
                .append("- `changed`: 해당 구성요소나 정의가 실제로 달라졌다. 변경 후 전체 파일과 무결성 정보를 반환한다.\n")
                .append("- `unchanged`: 확인했지만 달라지지 않았다. 파일 대신 확인 근거와 코드 위치를 적는다.\n")
                .append("- 결과를 확인하지 못했다면 `unchanged`로 추정하지 말고 `확인 불가`와 이유를 적는다.\n\n")
                .append("### 도메인 문서 반환 규칙\n\n")
                .append("- `도메인 반영`은 반드시 `changed` 또는 `unchanged` 중 하나로 적는다. ")
                .append("구현 완료 여부와 도메인 변경 여부는 서로 다른 판단이다.\n")
                .append("- `changed`: 신규 파일을 만들었거나 기존 도메인 정의가 달라진 경우다. ")
                .append("`domains/<도메인>/<모듈>.md`의 **변경 후 전체 문서**를 반환한다. ")
                .append("diff·patch·바뀐 문단만 보내지 않는다.\n")
                .append("- `unchanged`: 구현했지만 도메인 정의가 달라지지 않은 경우다. ")
                .append("도메인 파일은 보내지 않고, 변경 없음으로 판단한 이유와 확인한 코드 위치를 적는다.\n")
                .append("  사전에 도메인이 특정되지 않은 항목이면 도메인·모듈·대상 경로는 `해당 없음`으로 적을 수 있다.\n")
                .append("- 경로는 영문 소문자와 하이픈을 사용한 `domains/<도메인>/<모듈>.md` 한 단계 구조로 적는다. ")
                .append("요청서에 경로가 없더라도 변경이 생겼다면 개발자가 실제 책임 도메인과 모듈을 식별해 적는다.\n")
                .append("- 기존 문서를 반환할 때는 관련 없는 내용과 기존 앵커를 보존한다. ")
                .append("같은 도메인 파일이 여러 구현 항목에 걸리면 파일은 한 번만 반환하고 각 항목에 같은 경로를 적는다.\n")
                .append("- 비밀키·토큰·비밀번호·실제 개인정보는 문서와 예시에 넣지 않는다. ")
                .append("암호화 키처럼 운영에서 주입되는 값은 설정 이름이나 참조 위치만 적는다.\n\n")
                .append("### 도메인 문서에 반드시 담을 내용\n\n")
                .append("해당하지 않는 절은 `해당 없음`과 이유를 남긴다. 이미 저장소에 있는 문서는 그 구조를 유지하면서 ")
                .append("아래 사실이 빠지지 않게 갱신한다.\n\n")
                .append("1. **책임과 범위** — 이 모듈이 맡는 업무, 시작·종료 조건, 맡지 않는 범위\n")
                .append("2. **용어와 식별자** — 업무 용어, 코드값, 상태값, 외부·내부 식별자의 의미\n")
                .append("3. **업무 규칙과 흐름** — 사전 조건, 판단 조건, 처리 순서, 상태 전이, 예외, 재처리·멱등성 규칙\n")
                .append("4. **API 계약** — 메서드·경로, 인증·권한, 요청/응답 필드와 필수 여부, 오류 조건·코드\n")
                .append("5. **데이터 계약** — 저장 대상, 필드·자료형·필수 여부·키·제약, 이력·보존, 마이그레이션·기존 데이터 처리\n")
                .append("6. **적용 범위** — 시스템·기관·채널·사용자 조건과 허용/차단 규칙\n")
                .append("7. **외부 연동·이벤트·배치** — 송수신 주체, 메시지/파라미터, 동기·비동기, 시간 제한·재시도·실패 복구\n")
                .append("8. **구현 근거** — 구현 저장소·모듈·커밋 SHA와 핵심 코드 또는 설정 위치\n\n")
                .append("### 파일 식별과 무결성\n\n")
                .append("`changed` 파일마다 기준 커밋 SHA, 대상 경로, 파일 SHA-256, 바이트 크기를 적는다. ")
                .append("Builder는 다음 단계에서 이 값으로 어느 기획 저장소 판을 갱신할지와 파일이 전송 중 바뀌지 않았는지를 확인한다.\n\n");

        out.append("## 화면 재동기 회신 양식\n\n");
        if (expectedBack.screens().isEmpty()) {
            out.append("대상 화면이 없습니다.\n\n");
        } else {
            out.append("각 구성요소에 `changed` 또는 `unchanged`를 하나씩 적는다. `changed`이면 반환 파일, ")
                    .append("`unchanged`이면 변경 없음 근거를 적는다.\n\n")
                    .append("| 시스템 | 화면ID | 구성요소 | 판정 | 반환 파일 또는 근거 |\n")
                    .append("|---|---|---|---|---|\n");
            for (var screen : expectedBack.screens()) {
                for (String component : screen.requiredComponents()) {
                    out.append("| ").append(value(screen.systemCode()))
                            .append(" | `").append(screen.screenId()).append("` | `")
                            .append(component).append("` | `<changed | unchanged>` | `<파일 경로 또는 확인 근거>` |\n");
                }
            }
            out.append('\n');
        }

        out.append("## 화면 외 구현 회신 양식\n\n");
        if (expectedBack.backendChanges().isEmpty()) {
            out.append("사전에 계획된 화면 외 구현 항목이 없습니다. 아래 ‘실제 도메인 변경 확인’과 ")
                    .append("‘요청 외 실제 구현 변경 확인’은 그래도 반드시 작성한다.\n\n");
        }
        int sequence = 1;
        for (var change : expectedBack.backendChanges()) {
            out.append("### ").append(sequence++).append(". ")
                    .append(value(change.target())).append("\n\n")
                    .append("- 요청 분류: `").append(value(change.category())).append("`\n")
                    .append("- 요청한 변경: ").append(value(change.changeDetail())).append("\n")
                    .append("- 구현 상태: `<완료 | 부분 완료 | 미구현 | 범위 제외>`\n")
                    .append("- 실제 구현 결과: `<최종 동작과 요청 대비 차이를 구체적으로 작성>`\n")
                    .append("- 구현 저장소: `<저장소 식별자 또는 URL>`\n")
                    .append("- 구현 모듈: `<애플리케이션/모듈명>`\n")
                    .append("- 구현 커밋 SHA: `<40자리 SHA>`\n")
                    .append("- 핵심 코드·설정 위치: `<파일 경로와 심볼명>`\n")
                    .append("- 검증 결과: `<무엇을 어떻게 검증했고 성공/실패했는지>`\n\n")
                    .append("#### 도메인 반영\n\n")
                    .append("- 도메인 반영: `<changed | unchanged>`\n")
                    .append("- 도메인: `<영문 도메인명; unchanged이고 특정 도메인이 없으면 해당 없음>`\n")
                    .append("- 모듈: `<영문 모듈명; .md 제외; unchanged이고 특정 모듈이 없으면 해당 없음>`\n")
                    .append("- 대상 경로: `<changed면 domains/<도메인>/<모듈>.md; 해당 없으면 해당 없음>`\n")
                    .append("- 기준 기획 저장소 커밋 SHA: `<꾸러미 manifest.json의 planningRepoCommit>`\n")
                    .append("- 변경 내용 또는 변경 없음 근거: `<추가·수정·삭제한 정의 또는 변경이 없다고 판단한 이유>`\n")
                    .append("- 반환 파일: `<changed면 대상 경로의 전체 md 파일, unchanged면 없음>`\n")
                    .append("- 파일 SHA-256: `<changed일 때 필수>`\n")
                    .append("- 파일 크기(byte): `<changed일 때 필수>`\n\n");
        }

        out.append("## 실제 도메인 변경 확인 — 필수\n\n")
                .append("사전에 경로가 특정된 도메인 모듈이 0건이어도 실제 구현 과정에서 도메인 정의가 바뀌었는지 확인해 작성한다.\n\n")
                .append("- 확인 결과: `<변경 없음 | 변경 있음>`\n")
                .append("- 확인한 구현 범위: `<저장소·모듈·핵심 코드 또는 설정 위치>`\n")
                .append("- 판단 근거: `<변경 없음 또는 변경 있음으로 판단한 이유>`\n")
                .append("- 변경 도메인 문서: `<없음 또는 domains/<도메인>/<모듈>.md 목록>`\n")
                .append("- 영향받는 화면·API·데이터·권한·연동: `<없음 또는 영향 목록>`\n\n")
                .append("요청 당시 특정되지 않은 도메인 변경은 누락을 막기 위한 신고 정보다. ")
                .append("변경 후 전체 도메인 문서는 자동 수신 대상과 구분해 **별도 검토용 파일**로 함께 반환한다. ")
                .append("이 신고가 `manifest.json`의 자동 수신 대상을 늘리지는 않으며, 자동 검증과 반영은 후속 수신 단계에서 처리한다.\n\n")
                .append("## 요청 외 실제 구현 변경 확인 — 필수\n\n")
                .append("- 확인 결과: `<없음 | 있음>`\n")
                .append("- 변경 유형과 대상: `<화면 | API | 데이터 | 권한 | 배치 | 연동 | 기타 / 대상>`\n")
                .append("- 변경 이유: `<구현 중 추가 변경이 필요했던 이유>`\n")
                .append("- 실제 변경 내용과 영향: `<최종 동작, 영향 범위, 호환성 또는 주의사항>`\n")
                .append("- 근거와 반환 파일: `<커밋·코드 위치·문서 경로 또는 없음>`\n\n");

        appendTestReturnForms(out, content);
        appendReturnExamples(out);
    }

    /**
     * 단위·통합테스트 회신 양식.
     *
     * <p>⭐ <b>시나리오가 있으면 우리가 「무엇을 검증하나」를 먼저 적는다</b>(TC 마다 조건·행위·결과) — 개발은
     * 실제 결과·판정·근거만 채운다. 없으면 종전처럼 빈 양식이다 — 시나리오 없이도 계약은 성립한다.
     */
    private void appendTestReturnForms(StringBuilder out, DevelopmentRequestContent content) {
        if (content.hasTestScenarios()) {
            out.append("## 테스트 시나리오 안내\n\n")
                    .append("아래 TC 는 빌더가 FRD 의 완료 조건과 화면 외 구현 항목에서 미리 적은 **검증 대상**이다. ")
                    .append("`조건`·`행위`·`결과` 는 그대로 두고 **`실제 결과`·`판정`·`근거`** 만 채운다. ")
                    .append("`(mock)` 이 붙은 조건은 서버 응답을 꾸며서 만든다. ")
                    .append("빠진 케이스가 있으면 같은 꼴로 TC 를 더해도 된다 — 번호는 이어서 붙인다.\n\n");
        }
        out.append("## 단위테스트 결과 회신 양식\n\n");
        if (content.requiredChanges().isEmpty()) {
            out.append("대상 항목이 없습니다.\n\n");
        } else {
            int sequence = 1;
            for (var change : content.requiredChanges()) {
                int seq = sequence++;
                out.append("### ").append(seq).append(". ").append(value(change.target())).append("\n\n")
                        .append("- 판정 방법: ").append(change.verification() == null || change.verification().isBlank()
                                ? "⚠ 정해지지 않았습니다" : change.verification().strip()).append("\n");
                List<DevelopmentRequestContent.TestScenario> scenarios = content.unitScenarios(seq);
                if (scenarios.isEmpty()) {
                    out.append("- 수행 방법: `<테스트 종류·입력·조건>`\n")
                            .append("- 실제 결과: `<관찰한 결과>`\n")
                            .append("- 판정: `<성공 | 실패 | 미수행>`\n")
                            .append("- 근거: `<테스트명·로그·리포트 위치>`\n\n");
                } else {
                    out.append('\n');
                    appendScenarios(out, scenarios, "테스트명·로그·리포트 위치");
                }
            }
        }

        out.append("## 통합테스트 결과 회신 양식\n\n");
        if (content.acceptanceCriteria().isEmpty()) {
            out.append("대상 완료 조건이 없습니다.\n\n");
        } else {
            if (content.hasTestScenarios()) {
                appendScenarioIndex(out, content);
            }
            int sequence = 1;
            for (var criterion : content.acceptanceCriteria()) {
                int seq = sequence++;
                out.append("### ").append(seq).append(". ").append(criterion.content()).append("\n\n");
                List<DevelopmentRequestContent.TestScenario> scenarios = content.integrationScenarios(seq);
                if (scenarios.isEmpty()) {
                    out.append("- 시나리오: `<검증할 사용자 흐름>`\n")
                            .append("- 단계: `<1. ... 2. ...>`\n")
                            .append("- 기대 결과: `<완료 조건에 맞는 결과>`\n")
                            .append("- 실제 결과: `<관찰한 결과>`\n")
                            .append("- 판정: `<성공 | 실패 | 미수행>`\n")
                            .append("- 근거: `<스크린샷·로그·리포트 위치>`\n\n");
                } else {
                    appendScenarios(out, scenarios, "스크린샷·로그·리포트 위치");
                }
            }
        }
    }

    /** 완료 조건 ↔ TC 대응표 — bzp 시나리오의 {@code featureMappings} 자리. 뒤에 화면이 통과율을 셀 근거다. */
    private void appendScenarioIndex(StringBuilder out, DevelopmentRequestContent content) {
        out.append("| 완료 조건 | TC |\n|---|---|\n");
        int sequence = 1;
        for (var criterion : content.acceptanceCriteria()) {
            int seq = sequence++;
            String ids = content.integrationScenarios(seq).stream()
                    .map(DevelopmentRequestContent.TestScenario::id).collect(Collectors.joining(", "));
            out.append("| ").append(seq).append(". ").append(criterion.content()).append(" | ")
                    .append(ids.isBlank() ? "—" : ids).append(" |\n");
        }
        out.append('\n');
    }

    private void appendScenarios(StringBuilder out, List<DevelopmentRequestContent.TestScenario> scenarios,
                                 String evidenceHint) {
        for (var scenario : scenarios) {
            out.append("#### ").append(scenario.id()).append(" — ").append(scenario.title()).append("\n\n");
            if (scenario.dependency() != null) {
                out.append("- 의존: ").append(scenario.dependency()).append("\n");
            }
            if (scenario.condition() != null) {
                out.append("- 조건: ").append(scenario.condition()).append("\n");
            }
            out.append("- 행위: ").append(scenario.action()).append("\n")
                    .append("- 결과: ").append(scenario.expected()).append("\n")
                    .append("- 실제 결과: `<관찰한 결과>`\n")
                    .append("- 판정: `<성공 | 실패 | 미수행>`\n")
                    .append("- 근거: `<").append(evidenceHint).append(">`\n\n");
        }
    }

    private void appendReturnExamples(StringBuilder out) {
        out.append("## 작성 예시\n\n")
                .append("아래 예시는 작성 수준만 보여준다. 예시의 도메인과 경로를 실제 반환 대상으로 오해하지 않는다.\n\n")
                .append("### 변경 없음 예시\n\n")
                .append("- 확인 결과: `변경 없음`\n")
                .append("- 확인한 구현 범위: `member-api / ConsentService, ConsentController`\n")
                .append("- 판단 근거: `기존 동의 조회 계약을 그대로 호출했고 API·데이터·업무 규칙을 변경하지 않음`\n")
                .append("- 변경 도메인 문서: `없음`\n\n")
                .append("### 변경 있음 예시\n\n")
                .append("- 확인 결과: `변경 있음`\n")
                .append("- 도메인·모듈: `terms / third-party-consent`\n")
                .append("- 대상 경로: `domains/terms/third-party-consent.md`\n")
                .append("- 변경 내용: `동의 버전과 동의 일시 저장 규칙을 추가함`\n")
                .append("- 구현 근거: `member-api 커밋 0123456789abcdef0123456789abcdef01234567, ConsentService`\n")
                .append("- 반환 파일: `변경 후 전체 domains/terms/third-party-consent.md`\n")
                .append("- 파일 SHA-256·크기: `<실제 계산값>`\n");
    }

    private void appendRequirements(StringBuilder out,
                                    List<DevelopmentRequestContent.Requirement> items,
                                    String empty) {
        if (items.isEmpty()) {
            out.append(empty).append('\n');
            return;
        }
        for (var item : items) {
            out.append("- **").append(item.requirement()).append("**");
            if (item.note() != null && !item.note().isBlank()) {
                out.append("\n  - ").append(item.note().strip());
            }
            out.append('\n');
        }
    }

    /**
     * 갈래로 묶어 찍는다.
     *
     * <p>⭐ <b>이 묶음이 「공통 정책과 예외」를 대신하는 자리다.</b> 권한·정책이 구현 목록에
     * 섞여 눈에 덜 드는 것을 소제목으로 갚는다.
     */
    private void appendBackend(StringBuilder out, DevelopmentRequestContent content) {
        List<DevelopmentRequestContent.BackendChange> required = content.requiredChanges();
        if (required.isEmpty()) {
            out.append("화면 외 구현 항목이 없습니다.\n");
        } else {
            Map<String, List<DevelopmentRequestContent.BackendChange>> grouped = grouped(required);
            for (var entry : grouped.entrySet()) {
                out.append("### ").append(label(entry.getValue())).append("\n\n");
                for (var change : entry.getValue()) {
                    out.append("- **").append(value(change.target())).append("** — ")
                            .append(value(change.changeDetail())).append('\n');
                    if (change.requirementSeq() != null) {
                        out.append("  - 요구사항 ").append(change.requirementSeq()).append("번\n");
                    }
                    if (change.evidence() != null && !change.evidence().isBlank()) {
                        out.append("  - 근거: ").append(change.evidence().strip()).append('\n');
                    }
                    // ⚠ 비면 그대로 비었다고 적는다 — 지어내면 계약서가 거짓을 말한다.
                    out.append("  - 판정 방법: ")
                            .append(change.verification() == null || change.verification().isBlank()
                                    ? "⚠ 정해지지 않았습니다" : change.verification().strip())
                            .append('\n');
                }
                out.append('\n');
            }
        }
        List<DevelopmentRequestContent.BackendChange> unchanged = content.unchangedChanges();
        if (!unchanged.isEmpty()) {
            // ⭐ 백엔드는 as-is 가 빌더 손에 없다. 이 표가 그 대응물이다 — 「이건 안 봤나?」를 막는다.
            out.append("### 확인했고 변경 없음\n\n");
            out.append("아래는 조사했고 바꿀 것이 없다고 판정한 범위입니다.\n\n");
            out.append("| 갈래 | 대상 | 판정 근거 |\n|---|---|---|\n");
            for (var change : unchanged) {
                out.append("| ").append(value(change.categoryLabel()))
                        .append(" | ").append(cell(change.target()))
                        .append(" | ").append(cell(change.changeDetail())).append(" |\n");
            }
        }
    }

    private Map<String, List<DevelopmentRequestContent.BackendChange>> grouped(
            List<DevelopmentRequestContent.BackendChange> changes) {
        Map<String, List<DevelopmentRequestContent.BackendChange>> grouped = new LinkedHashMap<>();
        for (String category : CATEGORY_ORDER) {
            List<DevelopmentRequestContent.BackendChange> mine = changes.stream()
                    .filter(change -> category.equals(change.category())).toList();
            if (!mine.isEmpty()) {
                grouped.put(category, mine);
            }
        }
        // ⚠ 갈래가 늘었는데 위 순서에 안 적힌 것이 있으면 버리지 않고 뒤에 붙인다.
        changes.stream().map(DevelopmentRequestContent.BackendChange::category)
                .filter(category -> category != null && !grouped.containsKey(category))
                .distinct()
                .forEach(category -> grouped.put(category, changes.stream()
                        .filter(change -> category.equals(change.category())).toList()));
        return grouped;
    }

    private String label(List<DevelopmentRequestContent.BackendChange> group) {
        String label = group.get(0).categoryLabel();
        return label == null || label.isBlank() ? value(group.get(0).category()) : label;
    }

    /**
     * ⛔ <b>손으로 적지 않는다.</b> 꾸러미가 실제로 담은 파일 목록에서 낸다 — 손으로 적으면
     * {@code screens/} 폴더와 갈리고, 갈린 순간 어느 쪽이 맞는지 아무도 모른다.
     */
    private void appendInventory(StringBuilder out, DevelopmentRequestContent content,
                                 List<DevRequestPackage.Entry> entries) {
        if (content.screens().isEmpty()) {
            out.append("이 개발요청서에는 화면 작업이 없습니다.\n");
        } else {
            for (var screen : content.screens()) {
                out.append("### ").append(screen.displayName())
                        .append(" · `").append(screen.deliveryScreenId()).append("`\n\n");
                String prefix = "screens/" + system(screen) + "/" + screen.deliveryScreenId() + "/";
                List<DevRequestPackage.Entry> mine = entries.stream()
                        .filter(entry -> entry.path().startsWith(prefix)).toList();
                if (mine.isEmpty()) {
                    out.append("담긴 파일이 없습니다.\n\n");
                    continue;
                }
                for (var entry : mine) {
                    out.append("- `").append(entry.path()).append("` — ")
                            .append(entry.description()).append('\n');
                }
                out.append('\n');
            }
        }
        // ⛔ 자산을 가르는 기준을 여기 다시 적지 마라 — 정본은 DevRequestPackage.isAsset 하나다.
        //    manifest.json 의 assets.count 가 같은 기준으로 세므로 두 수가 갈리지 않는다.
        List<DevRequestPackage.Entry> assets = entries.stream()
                .filter(DevRequestPackage::isAsset).toList();
        if (!assets.isEmpty()) {
            out.append("### 공통 자산\n\n");
            out.append("목업이 부르는 css·js·이미지입니다. ")
                    .append("화면 폴더에서 `../assets/…` 로 그대로 열립니다 — 파일 ")
                    .append(assets.size()).append("장.\n");
        }
    }

    private String system(DevelopmentRequestContent.Screen screen) {
        return screen.systemCode() == null || screen.systemCode().isBlank()
                ? "unknown" : screen.systemCode();
    }

    private static String date(LocalDate date) {
        return date == null ? "—" : date.toString();
    }

    private static String value(String raw) {
        return raw == null || raw.isBlank() ? "—" : raw;
    }

    private static String cell(String raw) {
        if (raw == null || raw.isBlank()) {
            return "—";
        }
        return raw.replace("|", "\\|").replaceAll("\\R", " ");
    }
}
