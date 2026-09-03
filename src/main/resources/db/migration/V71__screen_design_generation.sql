create table builder.adk_builder_screen_design (
    project_id varchar(100) not null,
    system_code varchar(100) not null,
    screen_id varchar(200) not null,
    current_revision_id varchar(36),
    current_revision_no integer not null default 0,
    generation_state varchar(20) not null,
    generation_id varchar(36),
    generation_started_at timestamptz,
    requested_source_fingerprint varchar(64),
    requested_generator_version varchar(80),
    requested_schema_version varchar(80),
    failed_reason varchar(80),
    retry_after timestamptz,
    updated_at timestamptz not null default now(),
    primary key (project_id, system_code, screen_id),
    constraint adk_builder_screen_design_state_ck
        check (generation_state in ('RUNNING', 'DONE', 'FAILED'))
);

create table builder.adk_builder_screen_design_revision (
    revision_id varchar(36) primary key,
    project_id varchar(100) not null,
    system_code varchar(100) not null,
    screen_id varchar(200) not null,
    revision_no integer not null,
    source_fingerprint varchar(64) not null,
    generator_version varchar(80) not null,
    schema_version varchar(80) not null,
    content_json text not null,
    document_html text not null,
    bundle_path text not null,
    bundle_manifest_json text not null,
    created_at timestamptz not null default now(),
    unique (project_id, system_code, screen_id, revision_no)
);

create index adk_builder_screen_design_revision_screen_ix
    on builder.adk_builder_screen_design_revision(project_id, system_code, screen_id, created_at desc);

comment on table builder.adk_builder_screen_design is '화면설계서 화면별 현재 생성 상태와 정상 개정판 포인터';
comment on table builder.adk_builder_screen_design_revision is '화면설계서 불변 개정판과 캡처 번들 정보';
comment on column builder.adk_builder_screen_design_revision.bundle_path is '데이터 루트 아래 불변 캡처 번들의 안전한 상대 경로';
comment on column builder.adk_builder_screen_design_revision.bundle_manifest_json is '캡처 파일 이름과 해시 목록';

