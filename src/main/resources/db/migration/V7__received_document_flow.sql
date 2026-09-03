-- 받은 문서를 「AI 가 늘 정리한다」에서 「필요할 때만 읽고, 사람이 시킬 때 분석한다」로 바꾼다 (2026-08-15).
--
-- ⛔ V4·V6 을 고쳐서 될 일이 아니다. 이미 나간 마이그레이션이라 손대면 Flyway 체크섬이 깨진다.
--
-- 무엇이 바뀌나
--   ① 접수의 process_type(처리 대기·요구사항 대상·참고 문서)이 통째로 없어진다.
--      「참고 문서로 두고 싶으면 아무것도 안 하면 된다」가 그 자리를 대신한다.
--   ② 받은 문서의 preparation_state(정리 중심)가 content_state(첨부파일 내용 분석)로 좁아진다.
--   ③ 요구사항 분석 상태가 문서 상태와 **다른 축**으로 접수에 앉는다.
--   ④ 요구사항 표가 선다 — 목록의 「요구사항 현황」이 하드코딩한 '미생성' 이 아니라 이 표를 센다.
--
-- ⛔ 원본은 하나도 안 버린다. typed_content·원본 파일·extracted_content 는 그대로 두고,
--    normalized_content 는 「확인된 문서 내용」으로 옮겨 담는다.

-- ── ① 접수 — 처리 방향을 걷어내고 요구사항 분석 축을 놓는다 ───────────────────

alter table adk_builder_intake
    add column requirement_state text not null default 'NOT_STARTED'
        check (requirement_state in ('NOT_STARTED', 'RUNNING', 'REVIEW_REQUIRED', 'COMPLETED', 'FAILED'));

comment on column adk_builder_intake.requirement_state is
    '이 받은 문서에서 요구사항을 뽑는 일의 지금 상태. NOT_STARTED(미분석)·RUNNING(요구사항 분석 중)·REVIEW_REQUIRED(요구사항 검토 필요)·COMPLETED(완료)·FAILED(요구사항 분석 오류). ⛔ 문서 내용 분석 상태(received_document.content_state)와 다른 축이다 — 문서가 READY 여야 비로소 이 축이 움직인다. ⚠ NOT_STARTED 는 오류도 할 일도 아니다: 분석하지 않기로 한 참고 목적 문서가 여기 그대로 머문다.';

-- ⛔ 처리 방향(UNDECIDED·REQUIREMENTS·REFERENCE)은 개념째 폐기다. 열을 남겨 두면
--    「아직 안 골랐다」가 목록에 다시 살아난다 — 그것이 없애려던 바로 그 강조다.
alter table adk_builder_intake drop column process_type;

-- ── ② 받은 문서 — 준비 상태를 「내용 분석」으로 좁힌다 ─────────────────────────

alter table adk_builder_received_document
    add column content_state    text,
    add column document_content text,
    add column content_confirmed_at timestamptz;

-- 옛 상태를 새 상태로 옮긴다.
-- ⚠ 옛 상태 이름만 보고 갈면 안 된다 — **파일이 있느냐**가 새 규칙의 갈림길이기 때문이다.
--   ① PENDING: 직접 입력만 있거나 이미 글이 나왔으면 더 읽을 것이 없어 READY 이고,
--      파일이 있는데 아직 글이 없으면 멀티모달이 읽어야 하니 QUEUED 다.
--   ② REVIEW_REQUIRED 이면서 파일이 없는 것: 새 규칙에서 **직접 입력은 확인할 것이 없다.**
--      그대로 두면 파일도 없는 문서에 확인 모드가 열려 빈 칸을 확인하라고 하는 자리가 난다.
update adk_builder_received_document
   set content_state = case
           when preparation_state in ('EXTRACTING', 'NORMALIZING') then 'PROCESSING'
           when preparation_state = 'FAILED' then 'FAILED'
           when preparation_state = 'READY'  then 'READY'
           when preparation_state = 'REVIEW_REQUIRED'
               then case when server_path is null then 'READY' else 'REVIEW_REQUIRED' end
           when server_path is null then 'READY'
           when nullif(btrim(coalesce(extracted_content, '')), '') is not null then 'READY'
           else 'QUEUED'
       end;

-- ⛔ 사람이 고쳐 둔 정리본을 버리지 않는다. 정리본 → 뽑은 글 → 직접 입력 순으로 살린다.
update adk_builder_received_document
   set document_content     = coalesce(normalized_content, extracted_content, typed_content),
       content_confirmed_at = normalized_confirmed_at;

-- ⚠ 확인 대기로 남는 것에는 **확인 화면이 보여줄 글**이 있어야 한다. 새 흐름에서 그 자리는
--   extracted_content 인데 옛 흐름은 정리본을 normalized_content 에 뒀다 — 비어 있으면 옮겨 준다.
--   ⛔ 이걸 빠뜨리면 확인 화면의 칸이 민무늬로 뜨고, 확인 완료가 「비워 둘 수 없습니다」로 막힌다.
update adk_builder_received_document
   set extracted_content = coalesce(extracted_content, normalized_content)
 where content_state = 'REVIEW_REQUIRED';

alter table adk_builder_received_document
    alter column content_state set not null,
    alter column content_state set default 'READY',
    add constraint adk_builder_received_document_content_state_check
        check (content_state in ('QUEUED', 'PROCESSING', 'REVIEW_REQUIRED', 'READY', 'FAILED'));

-- ⛔ 옛 열은 지운다. 남겨 두면 「정리본」이라는 없어진 개념을 화면이 다시 집어 든다.
alter table adk_builder_received_document
    drop column preparation_state,
    drop column normalized_content,
    drop column normalized_confirmed_at;

comment on column adk_builder_received_document.content_state is
    '첨부파일 내용 분석의 지금 상태. QUEUED(내용 분석 대기)·PROCESSING(내용 분석 중)·REVIEW_REQUIRED(내용 확인 필요)·READY(등록 완료)·FAILED(문서 처리 오류). ⛔ 직접 입력과 서버 텍스트 추출이 성공한 첨부는 AI 를 안 거치고 처음부터 READY 다 — 이 축은 멀티모달로 읽어야 하는 문서에만 걸음이 있다. ⛔ 시도 이력은 여기 담지 않는다 — adk_builder_document_processing_run 이 진다.';

comment on column adk_builder_received_document.document_content is
    '확인된 문서 내용. 직접 입력이면 사람이 친 원문 그대로, 서버 텍스트 추출이면 뽑은 글, 멀티모달이면 사람이 확인·수정한 글이다. ⛔ 원문(typed_content·원본 파일)을 덮어쓰지 않는다 — 그것은 그대로 남는다. 요구사항 분석에 들어가는 것은 이 값이다.';

comment on column adk_builder_received_document.content_confirmed_at is
    '멀티모달 추출 결과를 사람이 확인해 마친 때. ⚠ 직접 입력과 서버 텍스트 추출은 확인할 것이 없어 NULL 로 남는다 — 이 값이 비었다고 「덜 된 문서」가 아니다.';

comment on column adk_builder_received_document.extracted_content is
    '서버나 멀티모달 AI 가 파일에서 뽑아낸 글. 평문은 그대로 읽고 PDF 는 pdftotext 이며, 그것으로 글자가 안 나오는 스캔 PDF·이미지만 멀티모달이 읽는다. 원문을 덮어쓰지 않는다. 직접 입력만 있는 문서는 뽑을 파일이 없어 NULL 로 남는다. ⚠ 사람이 확인한 뒤의 정본은 document_content 다.';

-- ── ③ 문서 처리 시도 — 「정리」가 빠지고 「멀티모달 읽기」와 「요구사항 분석」이 들어온다 ──

-- ⛔ 순서가 규칙이다: 헐겁게 만들고 → 값을 옮기고 → 조인다.
--    2026-08-16 에 여기서 데었다. 옛 값을 옮기기 **전에** 새 CHECK 를 걸었더니
--    빈 테스트 DB 에서는 통과하고 **실물에서만** 터졌다(23514, "violated by some row").
--    ⛔ 자료를 옮기는 마이그레이션에서 「조이기」를 위로 올리지 마라 — 초록이 거짓말을 한다.
alter table adk_builder_document_processing_run
    drop constraint adk_builder_document_processing_run_run_kind_check;

-- 옛 NORMALIZE 이력은 지우지 않는다 — 무엇을 왜 했는지가 이 표의 존재 이유다.
-- ⚠ 값이 새 CHECK 를 못 지나므로 가장 가까운 뜻인 UNDERSTAND 로 옮긴다.
--   (둘 다 「AI 가 문서를 읽었다」이고, 언제 무엇을 했는지는 시각과 error_message 가 여전히 진다.)
update adk_builder_document_processing_run set run_kind = 'UNDERSTAND' where run_kind = 'NORMALIZE';

alter table adk_builder_document_processing_run
    add constraint adk_builder_document_processing_run_run_kind_check
        check (run_kind in ('EXTRACT', 'UNDERSTAND', 'ANALYZE_REQUIREMENTS'));

comment on column adk_builder_document_processing_run.run_kind is
    '무엇을 한 시도인가. EXTRACT(파일에서 본문 뽑기 — 서버가 한다. 평문은 그대로 읽고 PDF 는 pdftotext 다. AI 가 아니다)·UNDERSTAND(스캔 PDF·이미지를 멀티모달 AI 가 읽는다)·ANALYZE_REQUIREMENTS(확인된 문서 내용에서 요구사항을 뽑는다 — 기획 저장소를 읽기 전용으로 참고한다) 셋 중 하나다. ⛔ NORMALIZE(1차 정리)는 2026-08-15 에 폐기됐다 — 받은 문서를 AI 가 늘 정리하지 않는다.';

-- ⛔ 인덱스를 걸기 **전에** 굳은 시도를 닫는다. 서버가 죽던 순간 돌던 줄이 남아 있으면
--    한 문서에 살아 있는 시도가 둘일 수 있고, 그러면 아래 유일 인덱스가 못 서서
--    **마이그레이션 전체가 실패한다**(위 CHECK 와 같은 부류다 — 빈 DB 에서는 안 걸린다).
-- ⚠ 어차피 이 서버는 지금 새로 뜨는 중이라 그 줄들은 이미 죽은 실행이다.
--    DocumentProcessingService.closeStuckRuns 가 부팅 때 하는 일을 여기서 한 번 먼저 하는 것이다.
update adk_builder_document_processing_run
   set state         = 'FAILED',
       error_message = coalesce(error_message, '서버가 다시 뜨면서 닫았다 — 실제로 끝났는지는 알 수 없다'),
       finished_at   = coalesce(finished_at, now())
 where state in ('WAITING', 'RUNNING');

-- ⛔ 같은 문서에 같은 갈래의 시도가 둘 겹치면 나중 것이 앞의 결과를 덮는다.
--    「같은 문서의 요구사항 분석을 동시에 두 번 시작할 수 없다」가 화면이 아니라 여기서 막힌다.
-- ⚠ 끝난 시도(SUCCEEDED·FAILED)는 이 인덱스에 안 들어온다 — 다시 시도하는 길은 그대로 열려 있다.
create unique index adk_builder_document_processing_run_one_live
    on adk_builder_document_processing_run (document_id, run_kind)
    where state in ('WAITING', 'RUNNING');

-- ── ④ 요구사항 ───────────────────────────────────────────────────────────────

create sequence adk_builder_requirement_seq;

-- ⭐ 번호는 프로젝트마다 1번부터이고 **재사용하지 않는다**(data-model §4).
--    max(number)+1 로 뽑으면 지운 번호가 되살아나고 동시 실행이 같은 번호를 집는다 —
--    프로젝트 줄에 카운터를 두고 UPDATE ... RETURNING 으로 집으면 그 둘이 한꺼번에 막힌다.
alter table adk_builder_project add column requirement_seq integer not null default 0;

comment on column adk_builder_project.requirement_seq is
    '이 프로젝트가 지금까지 채번한 요구사항 순번의 마지막 값. ⛔ 요구사항을 지우거나 제외해도 이 값은 안 줄어든다 — 번호 재사용 금지가 여기에 걸려 있다. 채번은 update ... set requirement_seq = requirement_seq + 1 returning 한 줄이고, 그 줄 잠금이 동시 실행을 막는다.';

create table adk_builder_requirement (
    id           varchar(7)   primary key
                              default lpad(nextval('adk_builder_requirement_seq')::text, 7, '0')
                              check (id ~ '^[0-9]{7}$'),
    project_id   varchar(7)   not null references adk_builder_project (id),
    intake_id    varchar(7)   not null references adk_builder_intake (id),
    number       integer      not null check (number > 0),
    title        varchar(255) not null check (btrim(title) <> ''),
    body         text         not null check (btrim(body) <> ''),
    screen_hints text,
    review_state text         not null default 'DRAFTED'
                              check (review_state in ('DRAFTED', 'CONFIRMED', 'EXCLUDED')),
    created_at   timestamptz  not null default now(),
    -- ⛔ 한 프로젝트 안에서 번호는 하나뿐이다. 채번이 어긋나면 여기서 시끄럽게 깨진다.
    unique (project_id, number)
);

alter sequence adk_builder_requirement_seq owned by adk_builder_requirement.id;

create index adk_builder_requirement_by_intake on adk_builder_requirement (intake_id);

comment on table  adk_builder_requirement              is '받은 문서 하나에서 뽑아낸 요구사항 낱개. ⚠ 「요구사항 명세서」가 아니다 — 산출물 사슬 재설계(2026-08-09)가 항목이 아니라 낱개로 바꿨다. 출처는 언제나 받은 문서 한 건이다.';
comment on column adk_builder_requirement.id           is '이 표의 기본키. 0 채운 일곱 자리 글자이고 DB 가 시퀀스로 채운다. ⛔ 사람이 보는 REQ 번호가 아니다 — 그것은 number 다.';
comment on column adk_builder_requirement.project_id   is '어느 프로젝트의 요구사항인가. 번호가 프로젝트마다 1번부터라 채번의 단위이기도 하다.';
comment on column adk_builder_requirement.intake_id    is '어느 받은 문서에서 나왔나. 목록의 「요구사항 현황」이 이 열로 센다.';
comment on column adk_builder_requirement.number       is '사람이 보는 순번. 화면에는 REQ-001 꼴로 적는다. ⛔ 문자열이 아니라 숫자다 — 문자열로 정렬하면 REQ-10 이 REQ-9 보다 앞에 선다.';
comment on column adk_builder_requirement.title        is '요구사항 한 줄 이름. AI 초안이 그대로 앉고 사람이 고칠 수 있다.';
comment on column adk_builder_requirement.body         is '요구사항 본문. ⛔ AI 가 추측해 지어낸 것은 안 담는다 — 받은 문서에 있는 요구만 담는다.';
comment on column adk_builder_requirement.screen_hints is 'AI 가 기획 저장소에서 찾은 관련 화면 후보. ⚠ BRD 의 최종 대상 화면과 다른 값이다 — 참고 정보라서 관계 표로 만들지 않고 글자로 둔다.';
comment on column adk_builder_requirement.review_state is '검토 상태. DRAFTED(생성 완료)·CONFIRMED(확정 완료)·EXCLUDED(제외). ⛔ 제외해도 줄을 지우지 않는다 — 번호를 유지해야 한다.';
comment on column adk_builder_requirement.created_at   is '만들어진 때. 여기서는 요구사항 분석이 초안을 앉힌 때다.';
