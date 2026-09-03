package com.bizplay.builder.account;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 계정의 데이터 접근. SQL 은 {@code src/main/resources/mapper/account/AccountMapper.xml} 에 있다.
 *
 * <p>⚠ <b>이름 규칙</b> — SQL 종류를 접두사로 쓴다({@code select}·{@code insert}·{@code update}).
 * ⛔ g2c 의 {@code ...ListPage}·{@code ...Action} 접미사는 안 쓴다 — 그건 그쪽의
 * 「목록화면 · 상세화면 · 액션」 흐름을 전제한 이름인데 이 저장소의 컨트롤러 모양이 다르다.
 *
 * <p>⚠ <b>표 이름에 {@code builder.} 를 빠뜨리지 마라.</b> 이 저장소의 표는 {@code public} 이 아니라
 * {@code builder} 스키마에 산다. Hibernate 는 {@code default_schema} 설정으로 알아서 붙였지만
 * <b>MyBatis 의 생 SQL 은 그것을 안 물려받는다</b> — 빠뜨리면 「표가 없다」로 죽는다.
 *
 * <p>⛔ <b>이미 앉은 줄을 고치는 문은 아래 둘뿐이다</b>({@link #updatePassword} ·
 * {@link #updateToTemporaryPassword}). 셋째를 더하기 전에 {@link Account} 머리의 경고를 먼저 읽어라 —
 * 2026-08-15 이전에는 이 자리들이 전부 엔티티의 상태 변경 메서드 + JPA 더티 체킹이었다.
 */
@Mapper
public interface AccountMapper {

    Optional<Account> selectById(String id);

    /** 로그인·중복 검사가 쓴다. 로그인 아이디는 전체에서 유일하다({@code unique}) — 많아야 한 줄이다. */
    Optional<Account> selectByLoginId(String loginId);

    /** 관리 목록이 쓴다. <b>로그인 아이디순이다</b> — 화면이 이 순서 그대로 그린다. */
    List<Account> selectAll();

    /**
     * 번호 여럿을 한 번에. 받은 문서 목록이 「올린 사람 이름」을 채울 때 쓴다.
     *
     * <p>⛔ <b>빈 목록으로 부르지 마라</b> — {@code in ()} 이 되어 SQL 이 깨진다.
     * 부르는 쪽이 먼저 비었나를 본다({@code IntakeController.uploaderNames}).
     * JPA 의 {@code findAllById} 는 빈 목록을 알아서 처리해 줬다 — <b>그 편의가 없어졌다.</b>
     */
    List<Account> selectByIdIn(@Param("ids") List<String> ids);

    /**
     * ⚠ {@code created_at} 을 넣지 않는다 — DB 의 {@code default now()} 가 채운다.
     * 방금 넣은 자바 쪽 객체는 그 값을 모르니, 필요하면 {@link #selectById} 로 한 번 되읽는다.
     * ⚠ {@code must_change_password} 는 넣는다 — 「처음 값이 무엇인가」는 업무가 정하는 것이라
     * {@link Account#create} 가 정본이다.
     */
    void insert(Account account);

    /**
     * 본인이 비밀번호를 <b>바꿨다</b>. 해시를 갈고 {@code must_change_password} 를 <b>거짓</b>으로 내린다 —
     * 최초 설정 흐름을 빠져나가는 자리다.
     *
     * <p>⛔ {@link #updateToTemporaryPassword} 와 <b>합치지 마라.</b> 둘은 해시를 간다는 것만 같고
     * 뜻이 정반대다. 한 메서드에 깃발 인자로 뭉개면 부르는 쪽에서 참·거짓이 뒤집혀도
     * 컴파일도 되고 예외도 안 난다 — 사람이 최초 설정 화면에 영영 갇히는 것으로만 드러난다.
     *
     * @return 바뀐 줄 수. 0 이면 그런 계정이 없다는 뜻이다
     */
    int updatePassword(@Param("accountId") String accountId,
                       @Param("passwordHash") String passwordHash);

    /**
     * 슈퍼관리자가 <b>임시 비밀번호를 다시 발급했다</b>. 해시를 갈고
     * {@code must_change_password} 를 <b>참</b>으로 올린다 — 최초 로그인 흐름을 한 번 더 밟게 된다.
     *
     * <p>⛔ {@link #updatePassword} 와 <b>합치지 마라</b> — 위 경고와 같은 까닭이다.
     *
     * @return 바뀐 줄 수. 0 이면 그런 계정이 없다는 뜻이다
     */
    int updateToTemporaryPassword(@Param("accountId") String accountId,
                                  @Param("passwordHash") String passwordHash);
}
