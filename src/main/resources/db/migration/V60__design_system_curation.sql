-- 추출 결과 위에 사람이 확정한 디자인 시스템 의미를 보존한다.
-- 원본 fragment와 CSS는 기획 저장소에 남고, 이 표에는 Builder의 큐레이션 오버레이만 둔다.
create table adk_builder_design_system_curation (
    project_id   varchar(7)   not null references adk_builder_project (id) on delete cascade,
    system_id    varchar(80)  not null,
    content_json text         not null check (btrim(content_json) <> ''),
    version      integer      not null default 1 check (version > 0),
    updated_at   timestamptz  not null default now(),
    updated_by   varchar(7)   references adk_builder_account (id),
    primary key (project_id, system_id)
);

comment on table adk_builder_design_system_curation is
    '추출 후보를 Builder에서 이름·분류·표시 순서·variant 역할로 확정한 프로젝트별 디자인 시스템 오버레이.';
comment on column adk_builder_design_system_curation.content_json is
    '원본 HTML이나 CSS가 아니라 사람이 확정한 컴포넌트 큐레이션 값만 담는다.';
comment on column adk_builder_design_system_curation.version is
    '동시에 편집할 때 뒤의 저장이 앞의 확정값을 조용히 덮지 않도록 확인하는 버전.';
