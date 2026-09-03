package com.bizplay.builder.project;

import java.time.Instant;

/**
 * 기획 레포 하나 = 클론 대상. 표는 {@code builder.adk_builder_project} 다.
 *
 * <p><b>2026-08-15 에 JPA 엔티티에서 MyBatis 가 읽는 값 묶음으로 바뀌었다.</b>
 *
 * <p>⛔ <b>setter 를 열지 마라. {@code markReady}·{@code markFailed}·{@code markReceiving}·
 * {@code replaceToken} 같은 상태 변경 메서드도 다시 만들지 마라.</b>
 * JPA 때는 찾아온 것을 고치면 트랜잭션 끝에 저장됐지만(더티 체킹) <b>MyBatis 에는 그것이 없다.</b>
 * 여기에 고치는 메서드를 두면 부르는 쪽은 저장된 줄 알고 DB 는 안 바뀐다 — <b>예외도 안 난다.</b>
 * 고치는 길은 {@link ProjectMapper#updateState} 와 {@link ProjectMapper#updateToken} 둘뿐이고,
 * 그 둘을 부르는 문은 {@link ProjectService} 에만 있다.
 *
 * <p>⚠ <b>읽개는 {@code getXxx()} 꼴을 지킨다</b> — 접수 쪽 값 묶음({@code Intake})은
 * {@code id()} 꼴인데 여기만 다른 것은 <b>화면(Thymeleaf)이 {@code ${p.name}} 으로 읽기 때문</b>이다.
 * 타임리프의 그 문법은 자바빈 규약({@code getName()})을 탄다 — 이름을 바꾸면 관리 화면 넷이
 * 통째로 「그런 속성이 없다」로 깨진다.
 */
public class Project {

    /**
     * 0 채운 일곱 자리 글자. {@code '0000001'} 꼴이다.
     *
     * <p>⚠ <b>이 값은 DB 밖으로 새어 나간다</b> — 주소({@code /projects/{id}/…})와
     * 워크트리 폴더 이름({@link ProjectPaths})이 이 값이다.
     *
     * <p>⛔ DB 에도 {@code default lpad(nextval(...))} 이 있지만 <b>거기에 기대지 마라</b> —
     * 채번은 {@link com.bizplay.builder.id.IdSequence} 가 한다. 까닭은 그 파일에 적어 뒀다.
     */
    private final String id;

    private final String name;
    private final String repoUrl;
    private final String defaultBranch;

    /**
     * 표준 화면ID 의 첫 마디({@code PS-WV-MRC-010-L01-S} 의 {@code PS}). 등록할 때 사람이 정한다.
     *
     * <p>⚠ <b>등록 시점에 고정된다 — 나중에 고쳐도 이미 채번된 화면엔 안 먹는다.</b> 클론이 앉으면
     * 바로 채번이 도는데(2026-08-20 표준 화면ID 설계), 이미 박힌 표준 ID 는 안 바꾸는 규칙이라서다.
     */
    private final String platformCode;

    /** 봉인된 GitLab 접근 토큰. ⛔ 평문으로 두지 않는다 — {@link #tokenNonce} 와 짝으로만 뜻이 있다. */
    private final byte[] sealedToken;

    private final byte[] tokenNonce;

    private final ProjectState state;

    /** 클론이 실패한 까닭. {@code FAILED} 일 때만 차고, 다시 받거나 성공하면 비운다. */
    private final String failureReason;

    /**
     * 만들어진 때. <b>DB 의 {@code default now()} 가 채운다</b> — 새로 만든 것에는 아직 없다.
     *
     * <p>⚠ 그래서 {@link ProjectService#register} 는 {@code insert} 한 뒤 한 번 되읽어서 돌려준다.
     */
    private final Instant createdAt;

    /**
     * MyBatis 가 조회 결과를 담을 때 쓴다 (매퍼 XML 의 {@code <constructor>}).
     *
     * <p>⛔ <b>인자 순서를 바꾸지 마라</b> — XML 의 {@code <arg>} 와 자리로 맞춘다.
     * {@code name}·{@code repoUrl}·{@code defaultBranch}·{@code platformCode}·{@code failureReason} 은
     * 다섯 다 글자라 뒤바뀌어도 컴파일도 되고 예외도 안 난다. {@code sealedToken}·{@code tokenNonce} 도 둘 다
     * {@code byte[]} 라 마찬가지다 — 봉인이 안 풀리는 것으로만 드러난다.
     */
    private Project(String id, String name, String repoUrl, String defaultBranch, String platformCode,
                    byte[] sealedToken, byte[] tokenNonce, ProjectState state,
                    String failureReason, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.repoUrl = repoUrl;
        this.defaultBranch = defaultBranch;
        this.platformCode = platformCode;
        this.sealedToken = sealedToken;
        this.tokenNonce = tokenNonce;
        this.state = state;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
    }

    /**
     * 새로 앉힐 것을 만든다.
     *
     * <p>⚠ 처음 상태는 {@code RECEIVING} 이다 — <b>「처음 값이 무엇인가」는 업무가 정한다.</b>
     * 그래서 DB 기본값에 기대지 않고 여기서 정하고 {@code insert} 가 그 값을 같이 넣는다.
     * ⚠ {@code createdAt} 은 담지 않는다 — DB 의 {@code default now()} 가 채운다.
     */
    public static Project create(String id, String name, String repoUrl, String defaultBranch,
                                 String platformCode, byte[] sealedToken, byte[] tokenNonce) {
        return new Project(id, name, repoUrl, defaultBranch, platformCode, sealedToken, tokenNonce,
                ProjectState.RECEIVING, null, null);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getRepoUrl() { return repoUrl; }
    public String getDefaultBranch() { return defaultBranch; }
    public String getPlatformCode() { return platformCode; }
    public byte[] getSealedToken() { return sealedToken; }
    public byte[] getTokenNonce() { return tokenNonce; }
    public ProjectState getState() { return state; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
}
