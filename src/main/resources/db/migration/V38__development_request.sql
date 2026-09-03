-- 완료한 FRD를 개발 조직에 전달할 개발요청서로 고정한다.

create sequence adk_builder_dev_request_seq;

alter table adk_builder_project add column dev_request_seq integer not null default 0;

comment on column adk_builder_project.dev_request_seq is
    '프로젝트 안에서 사람이 보는 DR 번호를 중복 없이 채번하기 위한 마지막 번호다.';

create table adk_builder_dev_request (
    id                varchar(7)   primary key
                                   default lpad(nextval('adk_builder_dev_request_seq')::text, 7, '0')
                                   check (id ~ '^[0-9]{7}$'),
    project_id        varchar(7)   not null references adk_builder_project (id),
    number            integer      not null check (number > 0),
    frd_id            varchar(7)   not null unique references adk_builder_frd (id),
    frd_number        integer      not null check (frd_number > 0),
    title             varchar(255) not null check (btrim(title) <> ''),
    system_code       varchar(50),
    facets            text,
    content_json      text         not null check (btrim(content_json) <> ''),
    delivery_state    text         not null default 'NOT_SENT'
                                   check (delivery_state in ('NOT_SENT', 'SENDING', 'SENT')),
    planner_comment   text,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    unique (project_id, number)
);

alter sequence adk_builder_dev_request_seq owned by adk_builder_dev_request.id;

comment on table adk_builder_dev_request is
    'FRD 작업 완료 시점의 요구사항, 화면 수정 내용, 화면 외 구현 요건과 확인 사항을 고정한 개발요청서다.';
comment on column adk_builder_dev_request.content_json is
    'FRD가 이후 바뀌어도 당시 전달 내용을 보존하기 위한 개발요청서 본문 스냅샷이다.';
comment on column adk_builder_dev_request.planner_comment is
    '기획자가 개발 조직에 추가로 전달하는 참고사항이다.';

create index adk_builder_dev_request_by_project
    on adk_builder_dev_request (project_id, number desc);
