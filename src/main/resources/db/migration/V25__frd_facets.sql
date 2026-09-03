-- FRD 분석을 시작할 때 기획자가 고른 접수처.
create table builder.adk_builder_frd_facet (
    frd_id     varchar(7)  not null references builder.adk_builder_frd (id) on delete cascade,
    project_id varchar(7)  not null,
    name       varchar(64) not null,
    primary key (frd_id, name),
    foreign key (project_id, name)
        references builder.adk_builder_project_facet (project_id, name)
);

comment on table builder.adk_builder_frd_facet is
    'FRD 요구사항 분석을 시작할 때 기획자가 고른 접수처. 화면을 고르지 않아도 AI 분석 범위를 제한하는 입력값으로 남는다.';
comment on column builder.adk_builder_frd_facet.frd_id is '접수처를 적용할 FRD.';
comment on column builder.adk_builder_frd_facet.project_id is '프로젝트 접수처 목록과 복합 외래키를 걸기 위한 프로젝트ID.';
comment on column builder.adk_builder_frd_facet.name is '프로젝트에 등록된 접수처 이름.';
