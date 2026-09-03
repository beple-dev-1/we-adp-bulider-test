package com.bizplay.builder.account;

import java.time.Instant;

/**
 * 빌더에 로그인하는 사람 하나. 표는 {@code builder.adk_builder_account} 다.
 *
 * <p><b>2026-08-15 에 JPA 엔티티에서 MyBatis 가 읽는 값 묶음으로 바뀌었다.</b>
 * 이것이 이 저장소의 <b>마지막 JPA 엔티티</b>였다 — 이제 하나도 안 남았다.
 *
 * <p>⛔ <b>setter 를 열지 마라. {@code changePassword}·{@code resetToTemporary} 같은 상태 변경
 * 메서드도 다시 만들지 마라.</b> JPA 때는 찾아온 것을 고치면 트랜잭션 끝에 저장됐지만(더티 체킹)
 * <b>MyBatis 에는 그것이 없다.</b> 여기에 고치는 메서드를 두면 부르는 쪽은 저장된 줄 알고
 * DB 는 안 바뀐다 — <b>예외도 안 난다.</b> 고치는 길은 {@link AccountMapper#updatePassword} 와
 * {@link AccountMapper#updateToTemporaryPassword} 둘뿐이다.
 *
 * <p>⚠ <b>그 둘은 뜻이 다르다.</b> 앞은 본인이 비밀번호를 <b>바꾼</b> 것이라
 * {@code must_change_password} 를 거짓으로 내리고, 뒤는 슈퍼관리자가 임시 비밀번호를
 * <b>재발급한</b> 것이라 참으로 올린다 — 최초 로그인 흐름을 한 번 더 밟게 된다.
 * ⛔ 하나로 뭉개지 마라.
 *
 * <p>⚠ <b>읽개는 {@code getXxx()}·{@code isXxx()} 꼴을 지킨다</b> — 접수 쪽 값 묶음({@code Intake})은
 * {@code id()} 꼴인데 여기만 다른 것은 <b>화면(Thymeleaf)이 {@code ${account.loginId}} 로 읽고
 * {@link BuilderUser#of} 가 이 이름들을 부르기 때문</b>이다. 타임리프의 그 문법은 자바빈 규약을
 * 탄다 — 이름을 바꾸면 계정 상세 화면이 통째로 「그런 속성이 없다」로 깨진다. ⛔ 「통일한다」며 손대지 마라.
 */
public class Account {

    /**
     * 0 채운 일곱 자리 글자. {@code '0000001'} 꼴이다.
     *
     * <p>⛔ DB 에도 {@code default lpad(nextval(...))} 이 있지만 <b>거기에 기대지 마라</b> —
     * 채번은 {@link com.bizplay.builder.id.IdSequence} 가 한다. 까닭은 그 파일에 적어 뒀다.
     */
    private final String id;

    /** 로그인할 때 치는 ID. 이메일이 아니다. 전체에서 유일하다. */
    private final String loginId;

    private final String name;
    private final String email;

    /** 비밀번호 해시. ⛔ 평문은 담지 않는다. */
    private final String passwordHash;

    private final boolean superAccount;

    /** 참이면 다음 로그인에서 비밀번호 변경 화면으로 강제로 보낸다. 새로 난 계정은 참이다. */
    private final boolean mustChangePassword;

    /**
     * 만들어진 때. <b>DB 의 {@code default now()} 가 채운다</b> — 새로 만든 것에는 아직 없다.
     * 되읽으면 찬다.
     */
    private final Instant createdAt;

    /**
     * MyBatis 가 조회 결과를 담을 때 쓴다 (매퍼 XML 의 {@code <constructor>}).
     *
     * <p>⛔ <b>인자 순서를 바꾸지 마라</b> — XML 의 {@code <arg>} 와 자리로 맞춘다.
     * {@code loginId}·{@code name}·{@code email}·{@code passwordHash} 는 넷 다 글자고
     * {@code superAccount}·{@code mustChangePassword} 는 둘 다 boolean 이라, 뒤바뀌어도
     * 컴파일도 되고 예외도 안 난다. 엉뚱한 사람 이름이 뜨거나 권한이 뒤집히는 것으로만 드러난다.
     */
    private Account(String id, String loginId, String name, String email, String passwordHash,
                    boolean superAccount, boolean mustChangePassword, Instant createdAt) {
        this.id = id;
        this.loginId = loginId;
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.superAccount = superAccount;
        this.mustChangePassword = mustChangePassword;
        this.createdAt = createdAt;
    }

    /**
     * 새로 앉힐 것을 만든다.
     *
     * <p>⚠ {@code mustChangePassword} 는 늘 참으로 난다 — <b>「처음 값이 무엇인가」는 업무가 정한다.</b>
     * DB 에도 같은 기본값이 있지만 거기 기대지 않고 여기서 정하고 {@code insert} 가 그 값을 같이 넣는다.
     * ⚠ {@code createdAt} 은 담지 않는다 — DB 의 {@code default now()} 가 채운다.
     */
    public static Account create(String id, String loginId, String name, String email,
                             String passwordHash, boolean superAccount) {
        return new Account(id, loginId, name, email, passwordHash, superAccount, true, null);
    }

    public String getId() { return id; }
    public String getLoginId() { return loginId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isSuperAccount() { return superAccount; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public Instant getCreatedAt() { return createdAt; }
}
