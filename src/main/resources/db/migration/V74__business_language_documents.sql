create table builder.adk_builder_business_document (
    project_id varchar(7) not null references builder.adk_builder_project (id),
    kind varchar(30) not null,
    content text not null,
    source_refs text not null default '[]',
    updated_at timestamptz not null default now(),
    updated_by varchar(7) references builder.adk_builder_account (id),
    primary key (project_id, kind),
    constraint adk_builder_business_document_kind_ck
        check (kind in ('POLICY', 'STANDARD_TERMS')),
    constraint adk_builder_business_document_content_ck
        check (length(trim(content)) > 0)
);

create table builder.adk_builder_business_document_seed (
    project_id varchar(7) primary key references builder.adk_builder_project (id),
    state varchar(20) not null,
    account_id varchar(7) not null references builder.adk_builder_account (id),
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    failed_reason varchar(80),
    constraint adk_builder_business_document_seed_state_ck
        check (state in ('RUNNING', 'DONE', 'FAILED'))
);

comment on table builder.adk_builder_business_document is
    '프로젝트가 정한 정책서와 표준용어 문서. 종류마다 Markdown 한 장이고 Builder DB가 정본이다.';
comment on column builder.adk_builder_business_document.source_refs is
    '최초 초안을 만들 때 참고한 domains 경로와 규칙 앵커의 JSON 배열. 사람이 고쳐도 근거 이력은 보존한다.';
comment on table builder.adk_builder_business_document_seed is
    'domains를 근거로 정책서와 표준용어 초안을 함께 만드는 프로젝트별 최초 실행 상태.';
