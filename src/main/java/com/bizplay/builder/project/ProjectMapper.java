package com.bizplay.builder.project;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 프로젝트의 데이터 접근. SQL 은 {@code src/main/resources/mapper/project/ProjectMapper.xml} 에 있다.
 *
 * <p>⚠ <b>이름 규칙</b> — SQL 종류를 접두사로 쓴다({@code select}·{@code insert}·{@code update}·
 * {@code delete}). ⛔ g2c 의 {@code ...ListPage}·{@code ...Action} 접미사는 안 쓴다 — 그건 그쪽의
 * 「목록화면 · 상세화면 · 액션」 흐름을 전제한 이름인데 이 저장소의 컨트롤러 모양이 다르다.
 *
 * <p>⚠ <b>표 이름에 {@code builder.} 를 빠뜨리지 마라.</b> 이 저장소의 표는 {@code public} 이 아니라
 * {@code builder} 스키마에 산다. Hibernate 는 {@code default_schema} 설정으로 알아서 붙였지만
 * <b>MyBatis 의 생 SQL 은 그것을 안 물려받는다</b> — 빠뜨리면 「표가 없다」로 죽는다.
 *
 * <p>⛔ <b>이미 앉은 줄을 고치는 문은 아래 둘뿐이다</b>({@link #updateState}·{@link #updateToken}).
 * 셋째를 더하기 전에 {@link Project} 머리의 경고를 먼저 읽어라 — 2026-08-15 이전에는 이 자리들이
 * 전부 엔티티의 상태 변경 메서드 + JPA 더티 체킹이었다.
 */
@Mapper
public interface ProjectMapper {

    Optional<Project> selectById(String id);

    /** 같은 이름이 이미 있나를 볼 때 쓴다 — 이름은 전체에서 유일하다({@code unique}). */
    Optional<Project> selectByName(String name);

    /** 관리 목록이 쓴다. <b>이름순이다</b> — 화면이 이 순서 그대로 그린다. */
    List<Project> selectAll();

    /**
     * 그 상태인 것만. 지금 부르는 곳은 {@code READY} 하나다(프로젝트 고르기·머리의 프로젝트 목록).
     *
     * <p>⚠ <b>이름순이다.</b> 목록과 고르개가 같은 순서로 떠야 사람이 헷갈리지 않는다.
     */
    List<Project> selectByState(ProjectState state);

    /**
     * ⚠ {@code created_at} 을 넣지 않는다 — DB 의 {@code default now()} 가 채운다.
     * ⚠ {@code state} 는 넣는다 — 「처음 값이 무엇인가」는 업무가 정하는 것이라
     * {@link Project#create} 가 정본이다.
     */
    void insert(Project project);

    /**
     * 상태와 실패 이유를 <b>같이</b> 간다. 이것이 상태를 고치는 유일한 문이다.
     *
     * <p>⛔ <b>실패 이유를 안 건드리는 짝을 더하지 마라.</b> 「준비됨·받는 중이 되면 이유를 비운다」가
     * 이 표의 규칙이고({@code V3__project.sql} 의 COMMENT), 둘을 갈라 놓으면 성공한 프로젝트에
     * 지난 실패 이유가 남아 상세 화면이 「실패했다」고 말한다.
     *
     * @return 바뀐 줄 수. 0 이면 그런 프로젝트가 없다는 뜻이다 —
     *         부르는 쪽({@link ProjectService})이 그것을 보고 던진다
     */
    int updateState(@Param("projectId") String projectId,
                    @Param("state") ProjectState state,
                    @Param("failureReason") String failureReason);

    /**
     * 봉인된 토큰만 갈아 끼운다. 이름 · URL · 브랜치는 안 건드린다 —
     * 「다시 시도는 네트워크 끊김을 낫게 하지만 만료를 못 낫게 한다」가 {@code project-setup} 의 결정이다.
     *
     * <p>⚠ 봉인과 nonce 는 <b>짝</b>이라 늘 같이 간다. 하나만 가는 문을 만들지 마라 — 봉인이 안 풀린다.
     *
     * @return 바뀐 줄 수. 0 이면 그런 프로젝트가 없다는 뜻이다
     */
    int updateToken(@Param("projectId") String projectId,
                    @Param("sealedToken") byte[] sealedToken,
                    @Param("tokenNonce") byte[] tokenNonce);

    /**
     * 테스트가 <b>트랜잭션을 끊고</b> 만든 줄을 손으로 치울 때 쓴다
     * ({@code CloneWorkerTest.토큰이_안_풀려도_실패가_커밋된다}).
     *
     * <p>⛔ 운영 화면에 「프로젝트 지우기」를 만들려고 이것을 쓰지 마라 — 접수 · 적용 구분 · AI 실행이
     * 이 표를 FK 로 가리키고 있어서 그냥 지우면 FK 위배로 죽는다. 지우는 기능은 <b>아직 없는 기능</b>이다.
     */
    void deleteById(String id);
}
