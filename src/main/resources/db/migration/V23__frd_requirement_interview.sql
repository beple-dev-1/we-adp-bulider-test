-- FRD 요구사항 인터뷰 — 질문·답변과 백엔드 변경 범위를 영속화한다.

create sequence adk_builder_frd_interview_message_seq;
create sequence adk_builder_frd_backend_change_seq;
create sequence adk_builder_frd_analysis_note_seq;

alter table adk_builder_frd drop constraint adk_builder_frd_state_check;
alter table adk_builder_frd
    add constraint adk_builder_frd_state_check
        check (state in ('ANALYZING', 'WAITING_ANSWER', 'ANALYSIS_FAILED', 'PICKED',
                         'DRAFTING', 'REVIEW', 'DONE'));

create table adk_builder_frd_interview_message (
    id              varchar(7)  primary key
                               default lpad(nextval('adk_builder_frd_interview_message_seq')::text, 7, '0')
                               check (id ~ '^[0-9]{7}$'),
    frd_id          varchar(7)  not null references adk_builder_frd (id) on delete cascade,
    seq             integer     not null check (seq > 0),
    role            text        not null check (role in ('AI', 'USER')),
    kind            text        not null check (kind in ('SUMMARY', 'QUESTION', 'ANSWER')),
    content         text        not null check (btrim(content) <> ''),
    question_topic  varchar(255),
    question_reason text,
    options_json    text,
    created_at      timestamptz not null default now(),
    unique (frd_id, seq)
);

alter sequence adk_builder_frd_interview_message_seq
    owned by adk_builder_frd_interview_message.id;

comment on table adk_builder_frd_interview_message is
    'FRD 요구사항 분석 중 AI와 사용자가 주고받은 인터뷰 메시지. 서버 재시작 뒤에도 질문과 답변을 이어 가는 정본이다.';
comment on column adk_builder_frd_interview_message.options_json is
    '질문 선택지의 JSON 문자열 배열. 직접 입력은 모든 질문에 공통으로 화면이 덧붙이므로 저장하지 않는다.';

create index adk_builder_frd_interview_message_by_frd
    on adk_builder_frd_interview_message (frd_id, seq);

create table adk_builder_frd_backend_change (
    id              varchar(7)   primary key
                                default lpad(nextval('adk_builder_frd_backend_change_seq')::text, 7, '0')
                                check (id ~ '^[0-9]{7}$'),
    frd_id          varchar(7)   not null references adk_builder_frd (id) on delete cascade,
    seq             integer      not null check (seq > 0),
    requirement_seq integer,
    category        text         not null check (category in ('API', 'DATA', 'PERMISSION',
                                                               'BATCH', 'NOTIFICATION', 'OTHER')),
    target          varchar(255) not null check (btrim(target) <> ''),
    change_detail   text         not null check (btrim(change_detail) <> ''),
    evidence        text,
    required        boolean      not null default true,
    created_at      timestamptz  not null default now(),
    unique (frd_id, seq)
);

alter sequence adk_builder_frd_backend_change_seq
    owned by adk_builder_frd_backend_change.id;

comment on table adk_builder_frd_backend_change is
    '요구사항 분석으로 확인한 백엔드 변경 범위. 프론트 변경은 adk_builder_frd_screen이 맡고 이 표는 API·데이터·권한·배치·알림만 맡는다.';
comment on column adk_builder_frd_backend_change.requirement_seq is
    'adk_builder_frd_item.seq와 연결되는 요구사항 차례. 근거를 특정 항목에 연결하지 못했으면 비어 있다.';
comment on column adk_builder_frd_backend_change.required is
    'true면 수정 필요, false면 조사했지만 변경 없음이다.';

create index adk_builder_frd_backend_change_by_frd
    on adk_builder_frd_backend_change (frd_id, seq);

create table adk_builder_frd_analysis_note (
    id         varchar(7)  primary key
                          default lpad(nextval('adk_builder_frd_analysis_note_seq')::text, 7, '0')
                          check (id ~ '^[0-9]{7}$'),
    frd_id     varchar(7)  not null references adk_builder_frd (id) on delete cascade,
    seq        integer     not null check (seq > 0),
    kind       text        not null check (kind in ('ACCEPTANCE_CRITERION', 'OPEN_ISSUE')),
    content    text        not null check (btrim(content) <> ''),
    created_at timestamptz not null default now(),
    unique (frd_id, kind, seq)
);

alter sequence adk_builder_frd_analysis_note_seq
    owned by adk_builder_frd_analysis_note.id;

comment on table adk_builder_frd_analysis_note is
    '분석 결과의 완료 기준과 확인 필요 항목. 미결 사항은 FRD 확정 뒤 이슈로 옮길 재료다.';

create index adk_builder_frd_analysis_note_by_frd
    on adk_builder_frd_analysis_note (frd_id, kind, seq);
