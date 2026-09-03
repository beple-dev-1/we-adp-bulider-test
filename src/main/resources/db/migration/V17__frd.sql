-- FRD 작업 — 요구사항 하나에서 to-be 화면까지. 산출물 사슬(받은문서·REQ·RD·BRD)과 디커플링돼 있다.
-- 정본: docs/superpowers/specs/2026-08-18-frd-fast-track-design.md (2026-08-18 병주 확정)

create sequence adk_builder_frd_seq;
create sequence adk_builder_frd_screen_seq;

alter table adk_builder_project add column frd_seq integer not null default 0;

comment on column adk_builder_project.frd_seq is
    '이 프로젝트가 지금까지 채번한 FRD 순번의 마지막 값. ⛔ FRD 를 지워도 줄지 않는다 — 번호 재사용 금지가 여기 걸려 있다. 채번은 update ... returning 한 줄이고 그 줄 잠금이 동시 실행을 막는다.';

create table adk_builder_frd (
    id                 varchar(7)   primary key
                                    default lpad(nextval('adk_builder_frd_seq')::text, 7, '0')
                                    check (id ~ '^[0-9]{7}$'),
    project_id         varchar(7)   not null references adk_builder_project (id),
    number             integer      not null check (number > 0),
    title              varchar(255) not null check (btrim(title) <> ''),
    system_code        varchar(50),
    source_kind        text         not null check (source_kind in ('PASTED', 'REQUIREMENT', 'BRD')),
    source_ref         varchar(50),
    source_text        text         not null check (btrim(source_text) <> ''),
    source_imported_at timestamptz,
    no_screen_reason   text,
    state              text         not null default 'ANALYZING'
                                    check (state in ('ANALYZING', 'ANALYSIS_FAILED', 'PICKED',
                                                     'DRAFTING', 'REVIEW', 'DONE')),
    failure            text,
    owner_account_id   varchar(7)   references adk_builder_account (id),
    created_at         timestamptz  not null default now(),
    updated_at         timestamptz  not null default now(),
    unique (project_id, number)
);

alter sequence adk_builder_frd_seq owned by adk_builder_frd.id;

comment on table  adk_builder_frd is
    'FRD(Functional Requirements Document) 작업. 요구사항 하나를 개발 가능하게 정리한 그릇이고 화면 목업 N장을 품는다. ⛔ 화면 0장이 정상이다 — 배치·권한·API 처럼 화면 일이 아닌 요건도 여기 앉는다.';
comment on column adk_builder_frd.number is
    '사람이 보는 순번. 화면에는 FRD-001 꼴로 적는다. ⛔ 문자열이 아니라 숫자다 — 문자열로 정렬하면 FRD-10 이 FRD-9 앞에 선다.';
comment on column adk_builder_frd.source_kind is
    '요구사항이 어디서 왔나. PASTED(붙여넣기)·REQUIREMENT(요구사항에서 가져옴)·BRD(BRD 에서 가져옴).';
comment on column adk_builder_frd.source_ref is
    '가져온 원본의 이름. REQ-012·BRD-003 같은 글자다. ⛔ 외래키가 아니다 — 사슬 넷이 나중에 별도 시스템으로 나갈 수 있어 일부러 글자로 둔다. 조인하지 마라.';
comment on column adk_builder_frd.source_text is
    '요구사항 원문 사본. ⭐ 가져온 경우에도 그때 베껴 담는다 — 원본이 나중에 바뀌어도 이 FRD 는 안 흔들린다.';
comment on column adk_builder_frd.no_screen_reason is
    'AI 가 「화면 일이 아니다」라고 본 까닭. 화면이 있으면 비어 있다.';
comment on column adk_builder_frd.state is
    '작업 상태. ANALYZING(화면 찾는 중)·ANALYSIS_FAILED(분석 오류)·PICKED(화면 확인 필요)·DRAFTING(수정 중)·REVIEW(검토 필요)·DONE(완료). ⛔ 실행 상태를 담는 표를 따로 만들지 마라 — 이 줄이 그 자리다.';
comment on column adk_builder_frd.failure is
    '분석이 실패한 까닭 요약. 화면에 「분석 오류」로만 뜨므로 왜인지를 아는 자리가 여기다.';

create index adk_builder_frd_by_project on adk_builder_frd (project_id);

create table adk_builder_frd_screen (
    id             varchar(7)   primary key
                                default lpad(nextval('adk_builder_frd_screen_seq')::text, 7, '0')
                                check (id ~ '^[0-9]{7}$'),
    frd_id         varchar(7)   not null references adk_builder_frd (id) on delete cascade,
    screen_id      varchar(100) not null check (btrim(screen_id) <> ''),
    screen_name    varchar(255),
    base_screen_id varchar(100),
    facet          varchar(50),
    pick_reason    text,
    state          text         not null default 'WAITING'
                                check (state in ('WAITING', 'GENERATING', 'GENERATED', 'FAILED')),
    html           text,
    changes        text,
    failure        text,
    generated_at   timestamptz,
    created_at     timestamptz  not null default now(),
    unique (frd_id, screen_id)
);

alter sequence adk_builder_frd_screen_seq owned by adk_builder_frd_screen.id;

comment on table  adk_builder_frd_screen is
    'FRD 하나가 고치는 화면 한 장. ⛔ 화면 하나를 짚는 이름은 FRD-003 + 화면ID 다 — MOCK- 같은 접두사를 되살리지 마라(2026-08-18 병주 확정).';
comment on column adk_builder_frd_screen.screen_id is
    '기획 저장소의 화면ID(wv-appr-write 꼴). ⛔ 빌더가 이름을 지어내지 않는다 — 새 화면이면 사람이 적는다.';
comment on column adk_builder_frd_screen.base_screen_id is
    '무엇을 베껴 시작했나. 기존 화면이면 screen_id 와 같고 새 화면이면 다르다.';
comment on column adk_builder_frd_screen.html is
    'AI 가 만든 to-be 화면 통째. ⚠ DB 에 사는 것은 이번 판 한정이다 — 기획 저장소로 밀 자리가 아직 없다(계획 2 Task 6·7 이 얼려 있다).';
comment on column adk_builder_frd_screen.changes is
    'AI 가 무엇을 왜 고쳤나 목록(JSON 배열 글자). ⭐ 사람이 html 을 훑지 않고 아는 유일한 길이고 다음 판의 개발요청서 재료다.';
