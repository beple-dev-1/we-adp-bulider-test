-- 적용 구분의 기계 식별자와 기획 저장소 업데이트 이력을 분리한다.

alter table builder.adk_builder_project_facet
    add column code varchar(64);

-- 기존 프로젝트는 표시 이름을 임시 코드로 보존한다. 관리 화면에서 추출기 코드로 바꿀 수 있다.
update builder.adk_builder_project_facet
   set code = name;

alter table builder.adk_builder_project_facet
    alter column code set not null,
    add constraint uq_project_facet_code unique (project_id, code),
    add constraint ck_project_facet_code_trimmed check (code = btrim(code) and code <> '');

comment on column builder.adk_builder_project_facet.code is
    '추출기 색인과 연결하는 고정 식별자. jeju·iksan처럼 파일과 API에서 쓰며 표시 이름과 분리한다.';
comment on column builder.adk_builder_project_facet.name is
    '기획자가 화면에서 보는 적용 구분 이름. 코드는 유지한 채 이름만 바꿀 수 있다.';

-- 표시 이름을 바꾸면 이미 등록된 접수의 표시 이름도 같은 트랜잭션에서 따라간다.
alter table builder.adk_builder_intake_facet
    drop constraint adk_builder_intake_facet_project_id_name_fkey,
    add constraint adk_builder_intake_facet_project_id_name_fkey
        foreign key (project_id, name)
        references builder.adk_builder_project_facet (project_id, name)
        on update cascade;

create table builder.adk_builder_repository_update (
    project_id     varchar(7)   primary key references builder.adk_builder_project (id),
    state          varchar(16)  not null,
    from_commit    varchar(64),
    current_commit varchar(64),
    changed        boolean,
    started_at     timestamptz  not null default now(),
    finished_at    timestamptz,
    failure_reason varchar(2000),
    constraint ck_repository_update_state
        check (state in ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

comment on table builder.adk_builder_repository_update is
    '클론된 기획 저장소의 최근 업데이트 시도. 프로젝트 클론 상태와 분리해 업데이트 실패가 기존 자료 열람을 막지 않는다.';
comment on column builder.adk_builder_repository_update.from_commit is '업데이트 직전 HEAD.';
comment on column builder.adk_builder_repository_update.current_commit is '업데이트가 끝난 뒤 HEAD.';
comment on column builder.adk_builder_repository_update.changed is '원격 변경이 실제로 반영됐는가.';
comment on column builder.adk_builder_repository_update.failure_reason is '실패 이유. 토큰이 섞인 주소는 저장 전에 가린다.';
