package com.bizplay.builder.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 받은 문서를 대표하는 요구사항의 데이터 접근.
 * SQL 은 {@code src/main/resources/mapper/intake/RequirementMapper.xml} 에 있다.
 *
 * <p>⚠ <b>표 이름에 {@code builder.} 를 빠뜨리지 마라.</b> MyBatis 는 {@code default_schema} 를
 * 안 물려받는다 — 빠뜨리면 「표가 없다」로 죽는다.
 *
 * <p>일반 검토에서 빼는 것은 {@link Requirement.ReviewState#EXCLUDED} 이다. 물리 삭제는
 * <b>정의서로 넘어가기 전 재분석 준비</b>에만 허용하며, 삭제해도 프로젝트의 번호 카운터는 되돌리지 않는다.
 */
@Mapper
public interface RequirementMapper {

    /**
     * 다음 번호를 <b>집어 온다</b>. 프로젝트 줄의 카운터를 하나 올리고 그 값을 돌려준다.
     *
     * <p>⛔ <b>{@code max(number) + 1} 로 바꾸지 마라.</b> 두 가지가 한꺼번에 깨진다 —
     * ① 제외하거나 지운 번호가 되살아난다 ② 같은 프로젝트의 두 분석이 동시에 같은 번호를 집는다
     * (그때 {@code unique (project_id, number)} 가 한쪽을 통째로 실패시킨다).
     * {@code update ... returning} 은 그 줄을 잠그므로 둘 다 막힌다.
     */
    int allocateNumber(@Param("projectId") String projectId);

    /** ⚠ {@code created_at} 을 넣지 않는다 — DB 의 {@code default now()} 가 채운다. */
    void insert(Requirement requirement);

    /** 한 받은 문서에서 나온 것 전부. <b>번호 오름차순</b>이다. */
    List<Requirement> selectByIntakeId(@Param("intakeId") String intakeId);

    /** 제외 여부와 무관하게 그 받은 문서에서 생성된 요구사항 전부를 센다. */
    int countAllByIntakeId(@Param("intakeId") String intakeId);

    /**
     * 재분석 전에 기존 요구사항을 지운다. ⛔ 번호 카운터는 건드리지 않는다.
     * 요구사항정의서가 구현되면 정의서 연결 여부를 이 삭제 조건에 반드시 추가한다.
     */
    int deleteForReanalysis(@Param("intakeId") String intakeId);

    /**
     * 한 프로젝트의 요구사항 전부. 목록 화면이 쓴다. <b>번호 오름차순</b>이다.
     *
     * <p>⚠ <b>제외한 것도 담는다.</b> 목록에서 사라지면 추적 매트릭스의 빈 줄과 어긋난다
     * (→ {@code ia} 설계). 가리는 것은 거르개의 몫이다.
     */
    List<Requirement> selectByProjectId(@Param("projectId") String projectId);

    java.util.Optional<Requirement> selectById(@Param("id") String id);

    /**
     * 확정·제외를 찍는다.
     *
     * <p>⭐ <b>상태와 사유를 같이 받는다.</b> 갈라 쓰면 그 사이에 「제외인데 사유가 없는」 순간이
     * 생겨 DB {@code CHECK} 가 저장을 통째로 거절한다.
     *
     * @param excludedReason 제외가 아니면 {@code null} — 되돌아갈 때 사유를 지우는 것도 이 자리다
     */
    void updateReviewState(@Param("id") String id,
                           @Param("reviewState") Requirement.ReviewState reviewState,
                           @Param("excludedReason") String excludedReason);

    /**
     * 사람이 고친 제목·본문을 앉히고 {@code updated_at} 을 <b>DB 시계로</b> 찍는다.
     * ⚠ 검토 상태는 안 건드린다 — 내용을 고친 것이 판단은 아니다.
     */
    void updateContent(@Param("id") String id,
                       @Param("title") String title,
                       @Param("body") String body);

    /**
     * 그 접수에 <b>아직 판단 안 한</b> 요구사항이 몇 건인가.
     * ⭐ 0 이면 그 접수가 {@link Intake.RequirementState#COMPLETED} 로 넘어간다.
     */
    int countUndecidedByIntakeId(@Param("intakeId") String intakeId);

    /**
     * 접수 여럿의 요구사항 건수를 한 번에 센다. 목록 화면의 「요구사항 현황」이 이것을 쓴다.
     *
     * <p>⛔ <b>빈 목록으로 부르지 마라</b> — {@code in ()} 이 되어 SQL 이 깨진다.
     * ⚠ <b>0건인 접수는 결과에 안 나온다</b> — 부르는 쪽이 없는 것을 0 으로 읽는다.
     */
    List<IntakeRequirementCount> countByIntakeIdIn(@Param("intakeIds") List<String> intakeIds);

    /** ⚠ 제외한 것은 세지 않는다 — 사람이 「몇 건 나왔나」로 읽는 숫자다. */
    record IntakeRequirementCount(String intakeId, int total, int confirmed) {
    }
}
