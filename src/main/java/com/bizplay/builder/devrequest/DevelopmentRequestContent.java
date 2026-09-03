package com.bizplay.builder.devrequest;

import java.util.List;

/**
 * 개발요청서에 고정하는 FRD 본문 스냅샷 — <b>디벨롭과의 계약서 본문</b>이다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}.
 *
 * <p>⛔ <b>스냅샷은 다시 만들지 않는다.</b> 그래서 칸을 더할 때마다 <b>옛 모양이 읽히는지</b>가
 * 먼저다 — 이미 나간 개발요청서는 새 칸 없이 저장돼 있다. 아래 조밀 생성자들이 그 자리다.
 */
public record DevelopmentRequestContent(String summary, String interviewSummary,
                                        List<Requirement> requirements,
                                        List<Screen> screens, List<BackendChange> backendChanges,
                                        List<Note> notes, List<TestScenario> testScenarios) {

    /** ⛔ 널을 빈 목록으로 눌러 둔다 — 널이면 화면과 {@code dev-request.md} 렌더가 통째로 죽는다. */
    public DevelopmentRequestContent {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        screens = screens == null ? List.of() : List.copyOf(screens);
        backendChanges = backendChanges == null ? List.of() : List.copyOf(backendChanges);
        notes = notes == null ? List.of() : List.copyOf(notes);
        testScenarios = testScenarios == null ? List.of() : List.copyOf(testScenarios);
    }

    /** 테스트 시나리오 칸이 없던(2026-08-27 이전) 스냅샷을 만드는 코드와 테스트를 위한 호환 생성자다. */
    public DevelopmentRequestContent(String summary, String interviewSummary,
                                     List<Requirement> requirements,
                                     List<Screen> screens, List<BackendChange> backendChanges,
                                     List<Note> notes) {
        this(summary, interviewSummary, requirements, screens, backendChanges, notes, List.of());
    }

    /** 시나리오를 채운 새 스냅샷. ⚠ 다른 칸은 한 글자도 안 바뀐다 — 계약 본문은 다시 만들지 않는다. */
    public DevelopmentRequestContent withTestScenarios(List<TestScenario> scenarios) {
        return new DevelopmentRequestContent(summary, interviewSummary, requirements, screens,
                backendChanges, notes, scenarios);
    }

    /** 인터뷰 요약 칸이 없던 개발요청 스냅샷을 만드는 코드와 테스트를 위한 호환 생성자다. */
    public DevelopmentRequestContent(String summary, List<Requirement> requirements,
                                     List<Screen> screens, List<BackendChange> backendChanges,
                                     List<Note> notes) {
        this(summary, null, requirements, screens, backendChanges, notes);
    }

    public record Requirement(int seq, String requirement, String nature, String natureLabel,
                              String note) {}

    /**
     * 고치는 화면 한 장.
     *
     * <p>⭐ {@code menuPath} 는 <b>빌더 DB</b>({@code IaScreenLink})에서 온다 —
     * ⛔ 클론의 {@code ia.md} 를 읽지 마라. 그것은 확정 시점에 굳는 스냅샷이라 낡은 답을 낸다.
     * ⚠ 기획자가 IA 에 아직 안 넣은 신규 화면은 <b>널이 정상</b>이고, 그건 검증 <b>경고</b>다.
     */
    public record Screen(String frdScreenId, String screenId, String screenName,
                         String systemCode, String menuPath, List<String> changes,
                         List<Marker> markers, List<Memo> memos,
                         String developmentScreenId, String fileName, String managementNumber,
                         String screenType, Boolean newScreen, String entryPoint,
                         List<Connection> connections) {

        public Screen {
            changes = changes == null ? List.of() : List.copyOf(changes);
            markers = markers == null ? List.of() : List.copyOf(markers);
            memos = memos == null ? List.of() : List.copyOf(memos);
            connections = connections == null ? List.of() : List.copyOf(connections);
        }

        /** 2026-08-26 이전 스냅샷을 만드는 코드와 테스트를 위한 호환 생성자다. */
        public Screen(String frdScreenId, String screenId, String screenName,
                      String systemCode, String menuPath, List<String> changes,
                      List<Marker> markers, List<Memo> memos) {
            this(frdScreenId, screenId, screenName, systemCode, menuPath, changes, markers, memos,
                    null, null, null, null, null, null, List.of());
        }

        public String displayName() {
            return screenName == null || screenName.isBlank() ? screenId : screenName;
        }

        /** 개발자에게 전달하는 화면 ID. {@code screenId}는 FRD 내부 작업 키일 수 있다. */
        public String deliveryScreenId() {
            return developmentScreenId == null || developmentScreenId.isBlank()
                    ? screenId : developmentScreenId;
        }

        /** 개발자가 만들거나 수정할 실제 파일명. */
        public String deliveryFileName() {
            return fileName == null || fileName.isBlank() ? deliveryScreenId() + ".html" : fileName;
        }

        public boolean isNewScreen() {
            return newScreen != null ? newScreen : screenId != null && screenId.startsWith("tmp-");
        }
    }

    /** 화면 정의서의 이동 행에서 고정한 개발 연결 안내다. */
    public record Connection(String anchor, String targetScreenId, String kind,
                             String label, String condition) {}

    /**
     * 목업 위에 사람이 찍어 둔 지시 한 건.
     *
     * <p>⭐ <b>변경 목록이 못 하는 말을 한다</b> — {@code elementLabel}·{@code selector} 가
     * 「목업의 어느 지점인가」를 가리킨다. 이것이 빠지면 「이 버튼을 여기로」가 계약에서 사라진다.
     */
    public record Marker(int markerNo, String elementLabel, String selector, String description) {}

    /** 화면에 달린 댓글형 메모 한 건. 작성자를 안고 간다 — 누가 한 말인지가 계약에서 중요하다. */
    public record Memo(String authorName, String content) {}

    /**
     * 화면 외 구현 한 줄.
     *
     * <p>⭐ <b>{@code required = false}(확인했고 변경 없음)도 담는다.</b> 백엔드는 as-is 가
     * 빌더 손에 없으므로(운영 소스를 안 읽는다) 이것이 그 대응물이다 —
     * 개발이 「이건 안 봤나?」를 되묻지 않게 한다.
     *
     * <p>⚠ <b>{@code required} 가 {@code Boolean} 인 것은 옛 스냅샷 때문이다.</b> 2026-08-24 앞의
     * 스냅샷은 만들 때 이미 「변경 없음」을 버려서 그 칸이 <b>없다</b> — 원시형이면 {@code false} 로
     * 읽혀 <b>구현할 것이 통째로 「변경 없음」으로 넘어간다.</b> 널은 {@code true} 다.
     */
    public record BackendChange(String category, String categoryLabel, String target,
                                String changeDetail, Integer requirementSeq, String evidence,
                                String verification, Boolean required) {

        public BackendChange {
            required = required == null || required;
        }
    }

    public record Note(String kind, String content) {}

    /**
     * 테스트 시나리오 한 건 — 개발이 채워 돌려보낼 <b>「무엇을 검증하나」</b>를 우리가 먼저 적는다.
     *
     * <p>서식은 bzp 빌더의 E2E 시나리오 TEMPLATE(의존·조건·행위·결과)을 빌렸다 — 이미 424장을 쓴 꼴이라
     * 개발 쪽에 낯설지 않다. 실행은 개발 몫이다(빌더는 대상 시스템을 띄울 수 없다).
     *
     * @param kind      {@code UNIT}(화면 외 구현 항목) · {@code INTEGRATION}(완료 조건)
     * @param targetSeq 어느 항목인가 — UNIT 은 {@link #requiredChanges()} 의 1부터 순번,
     *                  INTEGRATION 은 {@link #acceptanceCriteria()} 의 1부터 순번
     * @param id        {@code TC-001} 꼴. 회신에서 기계가 짝을 맞추는 열쇠다
     * @param title     한 줄 제목
     * @param dependency 선행 TC 제목. 없으면 널
     * @param condition 비정상·특수 상황만. 정상이면 널. mock 이 필요하면 {@code (mock)} 표기
     * @param action    사용자가 하는 행동
     * @param expected  눈으로 확인되는 결과
     */
    public record TestScenario(String kind, int targetSeq, String id, String title,
                               String dependency, String condition, String action, String expected) {

        public static final String UNIT = "UNIT";
        public static final String INTEGRATION = "INTEGRATION";

        public boolean isUnit() {
            return UNIT.equals(kind);
        }
    }

    public List<Requirement> developmentRequirements() {
        return requirements.stream().filter(item -> "DEVELOP".equals(item.nature())).toList();
    }

    public List<Requirement> operationRequirements() {
        return requirements.stream().filter(item -> "OPERATE".equals(item.nature())).toList();
    }

    public List<Requirement> excludedRequirements() {
        return requirements.stream().filter(item -> "OUTSIDE".equals(item.nature())).toList();
    }

    /**
     * 실제로 만들 화면 외 구현.
     *
     * <p>⛔ <b>스냅샷을 만들 때 걸러 내지 마라</b> — 「변경 없음」은 계약에 실려야 하는 확인 기록이다.
     * 거르는 자리는 <b>읽는 쪽</b>인 여기다.
     */
    public List<BackendChange> requiredChanges() {
        return backendChanges.stream().filter(BackendChange::required).toList();
    }

    /** 확인했고 바꿀 것이 없다고 판정한 것. 백엔드의 as-is 대응물이다. */
    public List<BackendChange> unchangedChanges() {
        return backendChanges.stream().filter(change -> !change.required()).toList();
    }

    public List<Note> acceptanceCriteria() {
        return notes.stream().filter(note -> "ACCEPTANCE_CRITERION".equals(note.kind())).toList();
    }

    public List<Note> openIssues() {
        return notes.stream().filter(note -> "OPEN_ISSUE".equals(note.kind())).toList();
    }

    /** 화면 외 구현 {@code seq}(1부터) 번째 항목의 단위테스트 시나리오. */
    public List<TestScenario> unitScenarios(int seq) {
        return testScenarios.stream()
                .filter(scenario -> scenario.isUnit() && scenario.targetSeq() == seq).toList();
    }

    /** 완료 조건 {@code seq}(1부터) 번째의 통합테스트 시나리오. */
    public List<TestScenario> integrationScenarios(int seq) {
        return testScenarios.stream()
                .filter(scenario -> !scenario.isUnit() && scenario.targetSeq() == seq).toList();
    }

    public boolean hasTestScenarios() {
        return !testScenarios.isEmpty();
    }
}
