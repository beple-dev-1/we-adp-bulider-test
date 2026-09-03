create table builder.adk_builder_business_document_revision (
    project_id varchar(7) not null,
    kind varchar(30) not null,
    revision_no integer not null,
    content text not null,
    source_refs text not null default '[]',
    change_type varchar(20) not null,
    created_at timestamptz not null default now(),
    created_by varchar(7) references builder.adk_builder_account (id),
    primary key (project_id, kind, revision_no),
    constraint adk_builder_business_document_revision_document_fk
        foreign key (project_id, kind)
        references builder.adk_builder_business_document (project_id, kind)
        on delete cascade,
    constraint adk_builder_business_document_revision_kind_ck
        check (kind in ('POLICY', 'STANDARD_TERMS')),
    constraint adk_builder_business_document_revision_change_type_ck
        check (change_type in ('INITIAL_DRAFT', 'EDIT', 'RESTORE')),
    constraint adk_builder_business_document_revision_content_ck
        check (length(trim(content)) > 0),
    constraint adk_builder_business_document_revision_no_ck
        check (revision_no > 0)
);

insert into builder.adk_builder_business_document_revision
    (project_id, kind, revision_no, content, source_refs, change_type, created_at, created_by)
select project_id, kind, 1, content, source_refs, 'INITIAL_DRAFT', updated_at, updated_by
  from builder.adk_builder_business_document;

comment on table builder.adk_builder_business_document_revision is
    '정책서와 표준용어를 저장할 때마다 남기는 전체 개정본. 과거 개정 복원도 새 개정본으로 기록한다.';
comment on column builder.adk_builder_business_document_revision.revision_no is
    '프로젝트와 문서 종류 안에서 1부터 증가하는 개정번호.';
comment on column builder.adk_builder_business_document_revision.change_type is
    'INITIAL_DRAFT 최초 생성, EDIT 직접 수정, RESTORE 이전 개정 복원.';
