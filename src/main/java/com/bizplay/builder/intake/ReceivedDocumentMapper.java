package com.bizplay.builder.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 받은 문서의 데이터 접근.
 * SQL 은 {@code src/main/resources/mapper/intake/ReceivedDocumentMapper.xml} 에 있다.
 *
 * <p>⚠ <b>이름 규칙</b> — SQL 종류를 접두사로 쓴다({@code select}·{@code insert}·{@code update}).
 * ⛔ g2c 의 {@code ...ListPage}·{@code ...Action} 접미사는 안 쓴다.
 *
 * <p>⚠ <b>표 이름에 {@code builder.} 를 빠뜨리지 마라.</b> MyBatis 는 {@code default_schema} 를
 * 안 물려받는다 — 빠뜨리면 「표가 없다」로 죽는다.
 *
 * <p>⛔ <b>원문({@code original_name}·{@code server_path}·{@code byte_size}·{@code typed_content})을
 * 고치는 {@code update} 를 만들지 마라.</b> 원본 보존이 규칙이다.
 */
@Mapper
public interface ReceivedDocumentMapper {

    /** 접수 하나에 문서 하나다 — DB 의 {@code UNIQUE (intake_id)} 가 그것을 지킨다. */
    Optional<ReceivedDocument> selectByIntakeId(String intakeId);

    /**
     * 목록 화면이 접수 여럿의 문서를 한 번에 끌어온다.
     *
     * <p>⛔ <b>빈 목록으로 부르지 마라</b> — {@code in ()} 이 되어 SQL 이 깨진다.
     * 부르는 쪽({@link IntakeController#list})이 먼저 비었나를 본다.
     */
    List<ReceivedDocument> selectByIntakeIdIn(@Param("intakeIds") List<String> intakeIds);

    /** 새 문서를 앉힌다. null 가능한 값은 XML에서 JDBC 종류를 명시한다. */
    void insert(ReceivedDocument document);

    /**
     * 내용 분석 상태만 옮긴다 — 「분석 중」처럼 <b>지나가는 자리</b>를 찍는 데 쓴다.
     *
     * <p>{@code READY} 로 옮길 때는 {@link #updateUnderstood} 로 문서 내용도 함께 채운다.
     */
    int updateContentState(@Param("documentId") String documentId,
                           @Param("state") ReceivedDocument.ContentState state);

    /**
     * 오류로 앉힌다. <b>사람에게 할 말을 같이 적는다</b> —
     * 상태만 바꾸면 화면이 「무엇이 잘못됐나」를 못 말한다.
     */
    int updateFailed(@Param("documentId") String documentId, @Param("reason") String reason);

    /**
     * 뽑아낸 글을 적고 다음 상태로 옮긴다.
     *
     * <p>⛔ <b>원문을 덮어쓰지 않는다</b> — {@code typed_content} 와 파일은 그대로 둔다.
     * 읽어 낸 글을 {@code document_content} 에도 앉히고 {@code READY} 로 옮긴다.
     */
    int updateUnderstood(@Param("documentId") String documentId,
                         @Param("extractedContent") String extractedContent);

    /**
     * 「내용 분석 다시 시도」 — 다시 줄에 세운다. <b>앞의 추출 결과를 비운다.</b>
     *
     * <p>⛔ 확인이 끝난 문서에 쓰지 마라. 그 판정은 {@link IntakeService#retryUnderstanding} 이 한다.
     */
    int updateRequeued(@Param("documentId") String documentId);

    /**
     * 아직 줄에 서 있는 문서의 접수 번호.
     *
     * <p>⛔ <b>재기동 때 이것을 다시 데려가지 않으면 영영 대기로 남는다.</b> 줄에 세우는 것과
     * 일꾼을 깨우는 것이 <b>다른 걸음</b>이라(하나는 트랜잭션 안, 하나는 커밋 뒤) 그 사이에
     * 서버가 죽거나 대기줄이 꽉 차면 <b>깨울 사람이 사라진다.</b> 화면에도 그 문서를 미는 버튼이 없다.
     * ⚠ V7 이 옛 자료를 옮기며 만든 대기 줄도 이 문이 데려간다.
     */
    List<String> selectQueuedIntakeIds();

    /**
     * 재기동 청소 — 굳은 「분석 중」을 오류로 앉힌다.
     *
     * <p>⛔ <b>지우지 마라.</b> 서버가 죽은 순간 돌던 문서가 영영 「내용 분석 중」으로 남는다.
     *
     * @return 바뀐 줄 수
     */
    int updateStuckProcessingToFailed(@Param("reason") String reason);
}
