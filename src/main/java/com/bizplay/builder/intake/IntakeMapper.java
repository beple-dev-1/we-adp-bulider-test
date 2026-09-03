package com.bizplay.builder.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 접수의 데이터 접근. SQL 은 {@code src/main/resources/mapper/intake/IntakeMapper.xml} 에 있다.
 *
 * <p>⚠ <b>이름 규칙</b> — SQL 종류를 접두사로 쓴다({@code select}·{@code insert}·{@code update}).
 * ⛔ g2c 의 {@code ...ListPage}·{@code ...Action} 접미사는 안 쓴다 — 그건 그쪽의
 * 「목록화면 · 상세화면 · 액션」 흐름을 전제한 이름인데 이 저장소의 컨트롤러 모양이 다르다.
 *
 * <p>⚠ <b>표 이름에 {@code builder.} 를 빠뜨리지 마라.</b> 이 저장소의 표는 {@code public} 이 아니라
 * {@code builder} 스키마에 산다. Hibernate 는 {@code default_schema} 설정으로 알아서 붙였지만
 * <b>MyBatis 의 생 SQL 은 그것을 안 물려받는다</b> — 빠뜨리면 「표가 없다」로 죽는다.
 *
 * <p>⛔ <b>{@code updateProcessType} 을 되살리지 마라 (2026-08-15 폐기).</b>
 * 처리 방향(요구사항 대상 / 참고 문서)이라는 개념 자체가 없어졌다.
 */
@Mapper
public interface IntakeMapper {

    Optional<Intake> selectById(String id);

    /** 재분석 준비와 분석 시작이 엇갈리지 않도록 접수 한 줄을 잠가 읽는다. */
    Optional<Intake> selectByIdForUpdate(String id);

    /**
     * 그 프로젝트의 접수 전부. <b>최근에 올린 것이 위다</b> — 목록 화면이 그 순서로 그린다.
     *
     * <p>⚠ 순서를 지우지 마라. 목록은 늘 그 프로젝트 것만 보고, 위가 최근이라는 것이 화면의 약속이다.
     */
    List<Intake> selectByProjectId(String projectId);

    /** ⚠ {@code uploaded_at} 을 넣지 않는다 — DB 의 {@code default now()} 가 채운다. */
    void insert(Intake intake);

    /**
     * 요구사항 분석을 <b>집는다</b> — 지금 돌고 있지 않을 때만 {@code RUNNING} 으로 바꾼다.
     *
     * <p>⭐ <b>「같은 문서의 요구사항 분석을 동시에 두 번 시작할 수 없다」가 여기서 막힌다.</b>
     * ⛔ 자바에서 먼저 읽어 보고 아니면 쓰는 식으로 바꾸지 마라 — 읽기와 쓰기 사이에 남이 들어오면
     * 두 실행이 같이 뜬다. 조건을 {@code where} 에 넣어야 읽는 시점과 쓰는 시점이 같아진다.
     *
     * @return 바뀐 줄 수. <b>0 이면 이미 돌고 있다</b>는 뜻이다 — 부르는 쪽이 그것으로 판정한다
     */
    int updateRequirementStateToRunning(@Param("intakeId") String intakeId);

    /** 끝난 결과를 적는다. ⚠ 집는 것과 달리 조건이 없다 — 일꾼만 부른다. */
    int updateRequirementState(@Param("intakeId") String intakeId,
                               @Param("state") Intake.RequirementState state);

    /**
     * 재기동 청소 — 굳은 {@code RUNNING} 을 오류로 앉힌다.
     *
     * <p>⛔ <b>지우지 마라.</b> 안 그러면 서버가 죽은 순간 돌던 분석이 영영 {@code RUNNING} 으로 남아
     * {@link #updateRequirementStateToRunning} 이 계속 0 을 돌려준다 — <b>그 문서는 다시 못 분석한다.</b>
     *
     * @return 바뀐 줄 수
     */
    int updateStuckRunningToFailed();

    /** 요구사항 분석을 시작하지 않은 접수만 지운다. 하위 자료는 FK가 함께 지운다. */
    int deleteNotStarted(@Param("projectId") String projectId,
                         @Param("intakeId") String intakeId);
}
