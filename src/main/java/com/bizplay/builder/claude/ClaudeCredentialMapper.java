package com.bizplay.builder.claude;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * 클로드 자격의 데이터 접근. SQL 은 {@code src/main/resources/mapper/claude/ClaudeCredentialMapper.xml} 에 있다.
 *
 * <p>⚠ <b>이름 규칙</b> — SQL 종류를 접두사로 쓴다({@code select}·{@code insert}·{@code update}·{@code delete}).
 * ⛔ g2c 의 {@code ...ListPage}·{@code ...Action} 접미사는 안 쓴다 — 그건 그쪽의
 * 「목록화면 · 상세화면 · 액션」 흐름을 전제한 이름인데 이 저장소의 컨트롤러 모양이 다르다.
 *
 * <p>⚠ <b>표 이름에 {@code builder.} 를 빠뜨리지 마라.</b> 이 저장소의 표는 {@code public} 이 아니라
 * {@code builder} 스키마에 산다. Hibernate 는 {@code default_schema} 설정으로 알아서 붙였지만
 * <b>MyBatis 의 생 SQL 은 그것을 안 물려받는다</b> — 빠뜨리면 「표가 없다」로 죽는다.
 */
@Mapper
public interface ClaudeCredentialMapper {

    Optional<ClaudeCredential> selectByAccountId(String accountId);

    Optional<String> selectAccountIdByEmail(@Param("email") String email);

    void insert(ClaudeCredential credential);

    /**
     * 이미 있는 사람의 자격을 갈아 끼운다.
     *
     * @return 바뀐 줄 수. <b>0 이면 그 사람 자격이 아직 없다는 뜻이다</b> —
     *         부르는 쪽이 이 값을 보고 {@link #insert} 로 간다.
     */
    int updateToken(@Param("accountId") String accountId,
                    @Param("sealedToken") byte[] sealedToken,
                    @Param("nonce") byte[] nonce);

    int updateConnectedCredential(@Param("accountId") String accountId,
                                  @Param("sealedToken") byte[] sealedToken,
                                  @Param("nonce") byte[] nonce,
                                  @Param("identity") ClaudeAccountIdentity identity);

    /** 테스트가 표를 비울 때 쓴다. */
    void deleteAll();
}
