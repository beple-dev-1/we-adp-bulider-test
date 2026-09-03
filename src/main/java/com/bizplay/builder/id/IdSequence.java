package com.bizplay.builder.id;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 기본키 채번 — 시퀀스를 읽어 <b>0 채운 일곱 자리 글자</b>로 찍는다.
 *
 * <p>정본: {@code docs/data-model.md} §0.
 *
 * <p>⛔ <b>왜 숫자가 아닌가.</b> 0 채움 정렬이 참인 것은 <b>폭이 고정일 때만</b>이다.
 * 폭이 섞이면 문자 비교라서 {@code '9' > '10'} 으로 뒤집힌다. 그래서 상한은 9,999,999 이고
 * 자릿수를 늘릴 수 없다. 넘으면 여덟 자리가 되어 {@code varchar(7)} 과 {@code CHECK} 가
 * <b>둘 다 거절한다</b> — 조용히 잘리지 않고 시끄럽게 깨진다.
 *
 * <p>⚠ <b>DB 에도 같은 시퀀스를 보는 {@code DEFAULT} 가 있다</b>(마이그레이션 {@code V1}·{@code V3}).
 * 같은 시퀀스라 둘이 충돌하지 않고, {@code psql} 로 손수 넣는 자리에서도 번호가 선다.
 * <b>앞으로 만드는 표는 이 모양을 베낀다</b> — 표마다 다시 고민하지 않는다.
 *
 * <p>⚠ 시퀀스는 트랜잭션을 안 탄다 — 롤백해도 번호는 안 돌아온다. <b>구멍이 나는 것이 정상이다.</b>
 */
@Component
public class IdSequence {

    /**
     * 채번할 표. <b>이름을 글자로 받지 않는 것은 일부러다</b> — 시퀀스 이름은 SQL 에
     * 자리표시자로 못 넣어 이어붙일 수밖에 없는데, 열거로 막으면 이어붙일 값이 여기 적힌 것뿐이 된다.
     */
    public enum Kind {
        ACCOUNT("adk_builder_account_seq"),
        PROJECT("adk_builder_project_seq"),
        INTAKE("adk_builder_intake_seq"),
        RECEIVED_DOCUMENT("adk_builder_received_document_seq"),
        DOCUMENT_PROCESSING_RUN("adk_builder_document_processing_run_seq"),
        AI_RUN("adk_builder_ai_run_seq"),
        /**
         * ⚠ <b>기본키의 채번이지 사람이 보는 {@code REQ-001} 이 아니다.</b> 그쪽은 프로젝트마다
         * 1번부터라 프로젝트 줄의 카운터가 따로 집는다({@code RequirementMapper.allocateNumber}).
         */
        REQUIREMENT("adk_builder_requirement_seq"),
        /**
         * 솔루션 목업이 실물과 다르다고 짚어 둔 표시.
         * ⚠ 화면ID 는 기획 저장소의 것이라 여기서 채번하지 않는다 — 이 번호는 표시 줄의 것이다.
         */
        MOCKUP_MISMATCH("adk_builder_mockup_mismatch_seq"),
        /** FRD 작업의 기본키. ⚠ 사람이 보는 FRD-001 이 아니다 — 그쪽은 FrdMapper.allocateNumber 다. */
        FRD("adk_builder_frd_seq"),
        /** FRD 안의 화면 한 줄. ⚠ 화면ID 는 기획 저장소 것이라 여기서 채번하지 않는다. */
        FRD_SCREEN("adk_builder_frd_screen_seq"),
        /** FRD 화면에 사용자가 작성한 댓글형 메모 한 줄. */
        FRD_SCREEN_MEMO_COMMENT("adk_builder_frd_screen_memo_comment_seq"),
        /** FRD 화면 요소에 사용자가 붙인 실행 마커 한 줄. */
        FRD_SCREEN_MARKER("adk_builder_frd_screen_marker_seq"),
        /** AI 가 쪼갠 요구사항 항목 한 줄. ⚠ 사람이 보는 차례는 {@code seq} 열이다. */
        FRD_ITEM("adk_builder_frd_item_seq"),
        /** FRD 요구사항 인터뷰에서 오간 메시지. */
        FRD_INTERVIEW_MESSAGE("adk_builder_frd_interview_message_seq"),
        /** FRD 요구사항 분석으로 확인한 백엔드 변경 한 줄. */
        FRD_BACKEND_CHANGE("adk_builder_frd_backend_change_seq"),
        /** FRD 분석의 완료 기준 또는 확인 필요 항목. */
        FRD_ANALYSIS_NOTE("adk_builder_frd_analysis_note_seq"),
        /** SRT 작업의 기본키. 화면 번호는 프로젝트별 카운터로 별도 채번한다. */
        SRT("adk_builder_srt_seq"),
        /** 개발요청서 내부 식별자 채번. 화면에 보이는 DR-001 번호는 프로젝트별 카운터로 따로 채번한다. */
        DEV_REQUEST("adk_builder_dev_request_seq"),
        /**
         * 개발요청 전송 <b>시도</b> 한 줄.
         * ⛔ 개발요청서마다 하나가 아니다 — 다시 누르면 줄이 하나 더 난다.
         */
        DEV_REQUEST_DELIVERY("adk_builder_dev_request_delivery_seq"),
        /** 프로젝트·시스템별 IA 정본. */
        IA_STRUCTURE("adk_builder_ia_structure_seq"),
        /** IA 의 Depth 1~5 한 행. */
        IA_ROW("adk_builder_ia_row_seq"),
        /** IA 확정 스냅샷. */
        IA_REVISION("adk_builder_ia_revision_seq"),
        /** 표준 화면ID 매핑표 한 줄. ⚠ 화면ID 는 기획 저장소 것이라 여기서 채번하지 않는다. */
        SCREEN_STANDARD_ID("adk_builder_screen_standard_id_seq"),
        /** 표준 화면ID 의 업무영역·기능그룹 코드표 한 줄. */
        SCREEN_ID_GROUP("adk_builder_screen_id_group_seq");

        private final String sequenceName;

        Kind(String sequenceName) {
            this.sequenceName = sequenceName;
        }

        String sequenceName() {
            return sequenceName;
        }
    }

    /** 일곱 자리 0 채움 글자의 꼴. DB 의 {@code CHECK (id ~ '^[0-9]{7}$')} 와 같은 것을 잰다. */
    private static final Pattern ID_SHAPE = Pattern.compile("^[0-9]{7}$");

    private final JdbcTemplate jdbc;

    /**
     * 시퀀스가 사는 스키마.
     *
     * <p>⛔ <b>맨몸 이름으로 부르면 안 된다.</b> 생 SQL 은 스키마를 안 붙여 준다 —
     * 커넥션의 {@code search_path}(기본값 {@code public})를 보고 「그런 relation 이 없다」로
     * 죽는다. 실물도 테스트도 똑같이 죽는다. 매퍼 XML 들이 표 이름에 {@code builder.} 를
     * 손으로 붙이는 것과 같은 까닭이다.
     *
     * <p>⚠ <b>왜 하필 Flyway 설정을 읽나.</b> 2026-08-15 까지는
     * {@code spring.jpa.properties.hibernate.default_schema} 를 읽었는데, 데이터 접근이 전부
     * MyBatis 로 넘어가면서 그 블록이 통째로 사라졌다. <b>시퀀스를 실제로 만드는 것이
     * Flyway 다</b>({@code V1}·{@code V3}) — 그러니 「시퀀스가 어느 스키마에 사나」의 정본은
     * 이제 이쪽이 맞다. ⛔ 그 설정을 {@code application-local.yml} 로 옮기지 마라(스프링 설정
     * 파일에 적힌 까닭 그대로다) — 옮기면 여기가 빈 문자열이 되어 채번이 {@code public} 을 본다.
     */
    private final String schema;

    public IdSequence(JdbcTemplate jdbc,
                      @Value("${spring.flyway.default-schema:}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema == null ? "" : schema.strip();
    }

    public String next(Kind kind) {
        String sequenceName = schema.isEmpty() ? kind.sequenceName() : schema + "." + kind.sequenceName();
        Long value = jdbc.queryForObject("select nextval('" + sequenceName + "')", Long.class);
        return "%07d".formatted(value);
    }

    /**
     * 밖에서 들어온 글자가 기본키의 꼴인가.
     *
     * <p>⛔ <b>타입이 더는 막아 주지 않아서 필요한 검사다.</b> 번호가 {@code Long} 이던 때는
     * 주소 조각이 숫자가 아니면 파싱이 막아 줬다. 글자가 된 지금은 {@code ".."} 도 그냥 통과해
     * {@link com.bizplay.builder.project.ProjectPaths} 에서 <b>클론 폴더 밖을 가리키는 경로</b>가 된다.
     * 주소·경로에서 온 값은 반드시 이 문을 지난다.
     */
    public static boolean isValidId(String id) {
        return id != null && ID_SHAPE.matcher(id).matches();
    }
}
