package com.bizplay.builder.ai;

import com.bizplay.builder.ai.AiRun.CheckerResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * AI 실행의 데이터 접근. SQL 은 {@code src/main/resources/mapper/ai/AiRunMapper.xml} 에 있다.
 *
 * <p>⚠ <b>이름 규칙</b> — SQL 종류를 접두사로 쓴다({@code select}·{@code insert}·{@code update}).
 * ⛔ g2c 의 {@code ...ListPage}·{@code ...Action} 접미사는 안 쓴다 — 그건 그쪽의
 * 「목록화면 · 상세화면 · 액션」 흐름을 전제한 이름인데 이 저장소의 컨트롤러 모양이 다르다.
 *
 * <p>⚠ <b>표 이름에 {@code builder.} 를 빠뜨리지 마라.</b> 이 저장소의 표는 {@code public} 이 아니라
 * {@code builder} 스키마에 산다. Hibernate 는 {@code default_schema} 설정으로 알아서 붙였지만
 * <b>MyBatis 의 생 SQL 은 그것을 안 물려받는다</b> — 빠뜨리면 「표가 없다」로 죽는다.
 * (JPA 때는 {@code AiRunService} 가 스키마 이름을 설정에서 읽어 손으로 이어 붙였다. 그 조립은 사라졌다 —
 * 이제 XML 에 그냥 적혀 있다.)
 */
@Mapper
public interface AiRunMapper {

    Optional<AiRun> selectById(String id);

    /** 재기동 청소가 쓴다 — 서버가 죽을 때 「돌고 있다」로 굳은 것들. */
    List<AiRun> selectByState(AiRunState state);

    /**
     * ⚠ {@code started_at} 을 넣지 않는다 — DB 의 {@code default now()} 가 채운다.
     *
     * @throws org.springframework.dao.DuplicateKeyException
     *         같은 일에 이미 {@code RUNNING} 이 있을 때. <b>판정은 자바가 아니라 부분 유일 인덱스가 한다</b> —
     *         MyBatis-Spring 이 그 제약 위반을 Spring 예외로 바꿔 던지고,
     *         {@link AiRunService#start} 가 잡아 {@link AlreadyRunningException} 으로 갈아 던진다
     */
    void insert(AiRun run);

    /**
     * <b>끝처리 한 자리.</b> {@code RUNNING} 인 것만 닫는다 — <b>여기가 「누가 이겼나」를 DB 에 맡기는 자리다.</b>
     *
     * <p>⛔ <b>무조건 덮어쓰는 짝을 새로 만들지 마라.</b> {@code where state = 'RUNNING'} 이 빠지면
     * 자연 종료한 일꾼이 이미 닫힌 성공을 덮어 <b>파일은 되돌아갔는데 화면은 「결과가 떴다」</b>가 된다.
     *
     * <p>⛔ <b>취소 여부를 자바에서 먼저 읽어 값을 골라 넘기지 마라.</b> 그래서 {@code CASE} 가
     * UPDATE 문 안에 있다 — 읽는 시점과 쓰는 시점이 같아야 그 틈으로 취소가 못 들어온다.
     *
     * @param state 일꾼이 판정한 끝 상태. <b>이대로 저장된다는 보장은 없다</b> —
     *              취소가 찍혀 있으면 DB 가 {@code CANCELLED} 로 갈아 쓴다
     * @return DB 가 <b>실제로 정한</b> 상태. <b>비어 있으면 남이 이미 닫은 것이라 내가 진 것이다</b> —
     *         부르는 쪽은 그때 되돌리기도 알림도 하지 않는다
     */
    Optional<AiRunState> updateToFinished(@Param("runId") String runId,
                                          @Param("state") AiRunState state,
                                          @Param("developerLog") String developerLog,
                                          @Param("checkerResult") CheckerResult checkerResult);

    /**
     * 그만두라는 표시만 찍는다. <b>상태는 안 건드린다</b> — 닫는 것은 일꾼 몫이다.
     *
     * @return 바뀐 줄 수. 0 이면 이미 끝났거나 이미 눌렀다는 뜻이다.
     *         ⚠ 지금 부르는 쪽은 이 값을 안 본다 — <b>둘 다 「아무 것도 안 한다」로 같기 때문</b>이지
     *         값이 쓸모없어서가 아니다
     */
    int updateCancelRequested(String runId);

    /**
     * 그만두라는 표시가 찍혔나. <b>일꾼이 프로세스를 띄우기 직전과 직후에 이것을 본다.</b>
     *
     * @return 그런 실행이 아예 없으면 {@code null}. ⚠ JdbcTemplate 때는 이 경우 예외였는데
     *         MyBatis 는 {@code null} 을 준다 — 부르는 쪽이 어차피 {@code Boolean.TRUE.equals} 로
     *         받으므로 「취소 안 눌렸다」로 같게 떨어진다
     */
    Boolean selectCancelRequested(String runId);
}
