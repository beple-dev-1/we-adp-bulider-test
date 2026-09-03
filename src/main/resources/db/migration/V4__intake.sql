-- 접수 — 받은 문서를 올린 자리에서 시작해 요구사항·정의서까지 걸어가는 한 건.
-- 이름·PK·COMMENT 규칙의 정본은 docs/data-model.md §0, 적용 구분은 같은 문서 §6 이다.
-- ⛔ 이 회차는 칸 1(받은 문서)만 세운다. 칸 2~8 은 계획 3 이다.
create sequence adk_builder_intake_seq;
create sequence adk_builder_received_document_seq;
create sequence adk_builder_document_processing_run_seq;

create table adk_builder_intake (
    id           varchar(7)   primary key
                              default lpad(nextval('adk_builder_intake_seq')::text, 7, '0')
                              check (id ~ '^[0-9]{7}$'),
    project_id   varchar(7)   not null references adk_builder_project (id),
    title        varchar(255) not null,
    uploaded_by  varchar(7)   not null references adk_builder_account (id),
    uploaded_at  timestamptz  not null default now(),
    process_type text         not null default 'UNDECIDED'
                              check (process_type in ('UNDECIDED', 'REQUIREMENTS', 'REFERENCE')),
    step         smallint     not null default 1
);

alter sequence adk_builder_intake_seq owned by adk_builder_intake.id;

comment on table  adk_builder_intake              is '접수 한 건. 받은 문서를 올린 자리에서 시작해 요구사항·정의서까지 여덟 칸을 걸어간다. 받은 문서 1건 = 접수 1건이다.';
comment on column adk_builder_intake.id           is '이 표의 기본키. 0 채운 일곱 자리 글자이고 DB 가 시퀀스로 채운다. 사람이 보는 산출물 번호가 아니다.';
comment on column adk_builder_intake.project_id   is '어느 프로젝트의 접수인가. 프로젝트 하나 = 기획 레포 하나다.';
comment on column adk_builder_intake.title        is '이 접수를 사람이 알아보는 이름. 등록 화면의 「문서명」이 그대로 온다.';
comment on column adk_builder_intake.uploaded_by  is '올린 사람. 빌더에 로그인한 계정을 가리킨다.';
comment on column adk_builder_intake.uploaded_at  is '만들어진(시작된) 때. 여기서는 문서를 등록한 때다.';
comment on column adk_builder_intake.process_type is '받은 문서를 어떻게 다룰지 사람이 고른 갈래. UNDECIDED(처리 대기)·REQUIREMENTS(요구사항 대상)·REFERENCE(참고 문서) 중 하나다. 정리본을 확인하기 전에는 UNDECIDED 이고, 참고 문서도 나중에 요구사항 대상으로 바꿀 수 있다. ⛔ 진행 단계가 아니라 갈래라서 화면에서 진행 단계 색을 주지 않는다.';
comment on column adk_builder_intake.step         is '지금 몇 번째 칸인가(1~8). 이 회차는 칸 1(받은 문서)만 세운다 — 칸 2~8 은 계획 3 이다. 화면의 「현재 단계」 열은 이 값과 받은 문서의 preparation_state 둘을 합쳐 만든다.';

create table adk_builder_received_document (
    id                      varchar(7)   primary key
                                         default lpad(nextval('adk_builder_received_document_seq')::text, 7, '0')
                                         check (id ~ '^[0-9]{7}$'),
    intake_id               varchar(7)   not null unique references adk_builder_intake (id),
    document_type           text         not null
                                         check (document_type in ('MEETING_MINUTES', 'WORK_REQUEST', 'PROPOSAL', 'OTHER')),
    original_name           varchar(255),
    server_path             text,
    byte_size               bigint,
    typed_content           text,
    meeting_at              timestamptz,
    attendees               text,
    preparation_state       text         not null default 'PENDING'
                                         check (preparation_state in
                                             ('PENDING', 'EXTRACTING', 'NORMALIZING', 'REVIEW_REQUIRED', 'READY', 'FAILED')),
    read_check_reason       text,
    extracted_content       text,
    normalized_content      text,
    normalized_confirmed_at timestamptz,
    -- ⛔ 파일 또는 직접 입력 중 하나 이상이어야 한다. 파일 쪽 셋은 같이 차거나 같이 빈다.
    check ((server_path is not null and original_name is not null and byte_size is not null)
           or nullif(btrim(typed_content), '') is not null)
);

alter sequence adk_builder_received_document_seq owned by adk_builder_received_document.id;

comment on table  adk_builder_received_document                         is '접수에 딸린 받은 문서 한 건. 선택 파일과 직접 입력 원문은 그대로 보존하고 AI 추출·정리 결과는 별도 열에 둔다. ⛔ intake_id 가 UNIQUE 다 — 「받은 문서 1건 = 접수 1건」이 규칙이고 다중 문서 등록을 만들 때 이 제약을 푼다.';
comment on column adk_builder_received_document.id                      is '이 표의 기본키. 0 채운 일곱 자리 글자이고 DB 가 시퀀스로 채운다.';
comment on column adk_builder_received_document.intake_id               is '어느 접수에 딸린 문서인가. 지금은 접수 하나에 문서 하나다.';
comment on column adk_builder_received_document.document_type           is '등록할 때 사람이 고른 문서 종류. MEETING_MINUTES(회의록)·WORK_REQUEST(과업요청서)·PROPOSAL(제안서)·OTHER(기타) 중 하나다. ⛔ 코드값은 영문이고 화면에 뜨는 한글은 자바 enum 이 표시 이름으로 든다 — 문구를 다듬는 데 이 CHECK 가 안 움직인다. 우선 분류와 검색에 쓴다. 종류에 따라 요구사항 추출 방식이 달라진다고 아직 정하지 않았다.';
comment on column adk_builder_received_document.original_name           is '선택 첨부파일의 원래 이름. 파일이 없으면 NULL 이다. ⛔ 이 값을 경로에 그대로 쓰지 마라 — 경로 구분자와 상위 이동 표기를 걷어낸 이름을 따로 만든다.';
comment on column adk_builder_received_document.server_path             is '서버 디스크에 실제로 앉은 자리. 글자를 만드는 곳은 ProjectPaths 하나뿐이다. 파일이 없으면 NULL 이다.';
comment on column adk_builder_received_document.byte_size               is '파일 크기(바이트). 목록에 보여주고 올릴 때 상한을 재는 데 쓴다. 파일이 없으면 NULL 이다.';
comment on column adk_builder_received_document.typed_content           is '사람이 직접 입력한 원문. 파일과 함께 있으면 AI 정리의 보충 설명이며, 어느 쪽이든 원문으로 그대로 보존한다.';
comment on column adk_builder_received_document.meeting_at              is '회의 일시. 문서 종류가 회의록일 때만 쓰는 선택 입력이다. AI 가 찾지 못한 값을 임의로 채우지 않는다.';
comment on column adk_builder_received_document.attendees               is '참석자. 문서 종류가 회의록일 때만 쓰는 선택 입력이다. AI 가 찾지 못한 값을 임의로 채우지 않는다.';
comment on column adk_builder_received_document.preparation_state       is '문서 준비의 지금 상태. PENDING(내용 처리 대기)·EXTRACTING(내용 추출 중)·NORMALIZING(내용 정리 중)·REVIEW_REQUIRED(정리 내용 확인)·READY(준비 완료)·FAILED(문서 처리 오류). 직접 입력만 있으면 EXTRACTING 을 건너뛴다. ⛔ 시도 이력은 여기 담지 않는다 — adk_builder_document_processing_run 이 진다.';
comment on column adk_builder_received_document.read_check_reason       is '올린 파일에서 글자가 나오는지 잰 결과의 한 줄 설명(DocumentReadCheck). 못 읽는 문서도 원본은 보존하고 올리기를 막지 않는다 — 막는 것은 AI 에 넣는 것이다. 읽히면 NULL 로 둔다.';
comment on column adk_builder_received_document.extracted_content       is '서버 AI API 가 파일 본문에서 뽑아낸 글. 원문을 덮어쓰지 않는다.';
comment on column adk_builder_received_document.normalized_content      is 'AI 가 1차 정리하고 사람이 확인·수정할 본문. 파일이나 직접 입력 원문을 덮어쓰지 않는다.';
comment on column adk_builder_received_document.normalized_confirmed_at is '사람이 AI 정리본을 확인해 마친 때. 이 값이 차야 처리 방향(요구사항 대상·참고 문서)을 고를 수 있다.';

create table adk_builder_document_processing_run (
    id              varchar(7)  primary key
                                default lpad(nextval('adk_builder_document_processing_run_seq')::text, 7, '0')
                                check (id ~ '^[0-9]{7}$'),
    document_id     varchar(7)  not null references adk_builder_received_document (id),
    run_kind        text        not null check (run_kind in ('EXTRACT', 'NORMALIZE')),
    state           text        not null check (state in ('WAITING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    provider_run_id text,
    error_message   text,
    input_tokens    bigint,
    output_tokens   bigint,
    cost_amount     numeric(14, 4),
    started_at      timestamptz,
    finished_at     timestamptz,
    created_at      timestamptz not null default now()
);

alter sequence adk_builder_document_processing_run_seq owned by adk_builder_document_processing_run.id;

comment on table  adk_builder_document_processing_run                 is '서버 소유 AI API 로 문서 본문을 추출하거나 정리한 시도 한 번. ⛔ 재시도는 이전 행을 덮어쓰지 않는다 — 두 번째 시도가 첫 번째를 지우면 무엇이 왜 실패했는지 사라진다.';
comment on column adk_builder_document_processing_run.id              is '이 표의 기본키. 0 채운 일곱 자리 글자이고 DB 가 시퀀스로 채운다.';
comment on column adk_builder_document_processing_run.document_id     is '어느 받은 문서를 처리한 시도인가.';
comment on column adk_builder_document_processing_run.run_kind        is '무엇을 한 시도인가. EXTRACT(파일에서 본문 뽑기)·NORMALIZE(뽑은 본문 1차 정리) 둘 중 하나다.';
comment on column adk_builder_document_processing_run.state           is '이 시도의 끝. WAITING(줄 섬)·RUNNING(도는 중)·SUCCEEDED(성공)·FAILED(실패) 중 하나다.';
comment on column adk_builder_document_processing_run.provider_run_id is 'AI API 쪽이 준 실행 식별자. 저쪽 로그와 맞춰 보는 데 쓴다.';
comment on column adk_builder_document_processing_run.error_message   is '실패한 까닭. FAILED 일 때만 찬다.';
comment on column adk_builder_document_processing_run.input_tokens    is '이 시도가 쓴 입력 토큰 수.';
comment on column adk_builder_document_processing_run.output_tokens   is '이 시도가 쓴 출력 토큰 수.';
comment on column adk_builder_document_processing_run.cost_amount     is '이 시도의 비용. 통화는 운영에서 하나로 고정한다.';
comment on column adk_builder_document_processing_run.started_at      is '실제로 돌기 시작한 때. 줄만 서고 아직 안 돌았으면 NULL 이다.';
comment on column adk_builder_document_processing_run.finished_at     is '끝난 때. 성공이든 실패든 찬다.';
comment on column adk_builder_document_processing_run.created_at      is '만들어진(시작된) 때. 여기서는 이 시도를 줄에 세운 때다.';

-- 적용 구분 — 표 둘 (정본: docs/data-model.md §6)
-- ⭐ (project_id, name) 을 통째로 FK 로 건 것이 이 모양의 값이다 —
--    목록에 없는 적용 구분이 산출물에 못 들어오고, 남의 프로젝트 값을 빌려 쓰는 것도 DB 가 막는다.
create table adk_builder_project_facet (
    project_id varchar(7)  not null references adk_builder_project (id),
    name       varchar(64) not null check (name = btrim(name) and name <> ''),
    primary key (project_id, name)
);

create table adk_builder_intake_facet (
    intake_id  varchar(7)  not null references adk_builder_intake (id),
    project_id varchar(7)  not null,
    name       varchar(64) not null,
    primary key (intake_id, name),
    foreign key (project_id, name) references adk_builder_project_facet (project_id, name)
);

comment on table  adk_builder_project_facet            is '그 프로젝트에 어떤 적용 구분이 있나. 값의 정본이다. 프로젝트 등록에서 사람이 넣는다. ⚠ 0행이면 그 프로젝트엔 적용 구분 축이 아예 없고 화면에 필터도 입력도 안 뜬다 — 「공통」이라는 값을 따로 만들지 않는다.';
comment on column adk_builder_project_facet.project_id is '어느 프로젝트의 적용 구분인가.';
comment on column adk_builder_project_facet.name       is '적용 구분 이름. 익산·제주처럼 한 시스템 안에서 요구사항이 갈리는 기준이다. 앞뒤 빈 칸을 다듬어 저장하고 빈 이름은 거절한다.';

comment on table  adk_builder_intake_facet            is '이 접수가 어느 적용 구분에 걸리나. 적용 구분이 있는 프로젝트에서는 하나 이상이다. 받은 문서 하나가 여러 적용 구분에 공통이면 해당하는 것을 모두 담는다.';
comment on column adk_builder_intake_facet.intake_id  is '어느 접수인가.';
comment on column adk_builder_intake_facet.project_id is '어느 프로젝트인가. ⛔ 중복이 아니라 (project_id, name) 을 통째로 프로젝트 적용 구분 목록에 FK 로 걸기 위한 열이다.';
comment on column adk_builder_intake_facet.name       is '고른 적용 구분 이름. 그 프로젝트의 목록에 있는 값만 들어온다.';
