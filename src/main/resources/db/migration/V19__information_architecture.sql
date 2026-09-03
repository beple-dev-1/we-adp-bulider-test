-- 메뉴구조도(IA)의 운영 정본. 저장소 ia.md 는 최초 입력과 확정 게시물일 뿐이다.

create sequence adk_builder_ia_structure_seq;
create sequence adk_builder_ia_row_seq;
create sequence adk_builder_ia_revision_seq;

create table adk_builder_ia_structure (
    id                 varchar(7)   primary key
                                    check (id ~ '^[0-9]{7}$'),
    project_id         varchar(7)   not null references adk_builder_project (id),
    system_code        varchar(50)  not null check (btrim(system_code) <> ''),
    state              text         not null check (state in ('DRAFT', 'CONFIRMED', 'PUBLISH_FAILED')),
    current_revision   integer      not null default 0 check (current_revision >= 0),
    version            integer      not null default 0 check (version >= 0),
    imported_hash      varchar(64)  not null check (imported_hash ~ '^[0-9a-f]{64}$'),
    imported_at        timestamptz  not null default now(),
    confirmed_at       timestamptz,
    confirmed_by       varchar(7)   references adk_builder_account (id),
    published_commit   varchar(64),
    publish_failure    text,
    updated_at         timestamptz  not null default now(),
    updated_by         varchar(7)   references adk_builder_account (id),
    unique (project_id, system_code)
);

create table adk_builder_ia_row (
    id               varchar(7)   primary key
                                  check (id ~ '^[0-9]{7}$'),
    structure_id     varchar(7)   not null references adk_builder_ia_structure (id) on delete cascade,
    row_order        integer      not null check (row_order > 0),
    path_key         varchar(1024) not null check (path_key ~ '^[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+){0,4}$'),
    depth1           varchar(255) not null check (btrim(depth1) <> ''),
    depth2           varchar(255),
    depth3           varchar(255),
    depth4           varchar(255),
    depth5           varchar(255),
    user_type        varchar(100),
    menu_type        varchar(100),
    screen_type      varchar(100),
    screen_id        varchar(100),
    updated_at       timestamptz  not null default now(),
    updated_by       varchar(7)   references adk_builder_account (id),
    unique (structure_id, row_order),
    unique (structure_id, screen_id),
    check (screen_id is null or btrim(screen_id) <> ''),
    check (depth2 is not null or (depth3 is null and depth4 is null and depth5 is null)),
    check (depth3 is not null or (depth4 is null and depth5 is null)),
    check (depth4 is not null or depth5 is null)
);

create table adk_builder_ia_revision (
    id               varchar(7)  primary key
                                 check (id ~ '^[0-9]{7}$'),
    structure_id     varchar(7)  not null references adk_builder_ia_structure (id) on delete cascade,
    revision         integer     not null check (revision > 0),
    snapshot_content text        not null,
    snapshot_hash    varchar(64) not null check (snapshot_hash ~ '^[0-9a-f]{64}$'),
    state            text        not null check (state in ('PUBLISHING', 'PUBLISHED', 'FAILED')),
    published_commit varchar(64),
    failure          text,
    created_at       timestamptz not null default now(),
    created_by       varchar(7)  not null references adk_builder_account (id),
    published_at     timestamptz,
    unique (structure_id, revision)
);

alter sequence adk_builder_ia_structure_seq owned by adk_builder_ia_structure.id;
alter sequence adk_builder_ia_row_seq owned by adk_builder_ia_row.id;
alter sequence adk_builder_ia_revision_seq owned by adk_builder_ia_revision.id;

comment on table adk_builder_ia_structure is
    '프로젝트·시스템별 메뉴구조도 정본. ⛔ 최초 가져오기 뒤 ia.md 를 다시 읽어 덮어쓰지 않는다.';
comment on column adk_builder_ia_structure.version is
    '낙관적 잠금 판번호. 같은 구조도를 연 두 사람이 뒤의 저장으로 앞의 저장을 덮지 않게 한다.';
comment on table adk_builder_ia_row is
    'IA 한 행. depth1~depth5 는 파생하지 않고 명시 저장한다. 중간 depth 가 빈 계층은 허용하지 않는다.';
comment on column adk_builder_ia_row.path_key is
    'ia.md 에 게시하는 안정적인 경로 식별자. depth 이름과 별도로 보존해 한글 이름 변경이 경로 키를 바꾸지 않게 한다.';
comment on column adk_builder_ia_row.screen_id is
    '기획 저장소 화면ID. 비어 있으면 화면 없는 메뉴다. 빌더가 새 화면ID 를 만들지 않는다.';
comment on table adk_builder_ia_revision is
    '확정 때의 결정적 ia.md 스냅샷과 게시 결과. Git 실패를 성공으로 보이지 않게 별도 상태로 남긴다.';
