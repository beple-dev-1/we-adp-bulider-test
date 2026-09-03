create sequence adk_builder_srt_seq;

alter table adk_builder_project add column srt_seq integer not null default 0;

alter table adk_builder_frd drop constraint if exists adk_builder_frd_source_kind_check;
alter table adk_builder_frd add constraint adk_builder_frd_source_kind_check
    check (source_kind in ('PASTED', 'REQUIREMENT', 'BRD', 'SRT'));

create table adk_builder_srt (
    id                  varchar(7) primary key
                                   default lpad(nextval('adk_builder_srt_seq')::text, 7, '0')
                                   check (id ~ '^[0-9]{7}$'),
    project_id          varchar(7) not null references adk_builder_project (id),
    number              integer not null check (number > 0),
    source_kind         text not null check (source_kind in ('DIRECT', 'FLOW')),
    flow_task_number    varchar(30),
    title               varchar(255) not null check (btrim(title) <> ''),
    content             text not null check (btrim(content) <> ''),
    source_json         text,
    owner_account_id    varchar(7) not null references adk_builder_account (id),
    bridge_frd_id       varchar(7) unique references adk_builder_frd (id),
    dev_request_id      varchar(7) unique references adk_builder_dev_request (id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    unique (project_id, number),
    check ((source_kind = 'DIRECT' and flow_task_number is null)
        or (source_kind = 'FLOW' and btrim(flow_task_number) <> ''))
);

alter sequence adk_builder_srt_seq owned by adk_builder_srt.id;
create index adk_builder_srt_by_project on adk_builder_srt (project_id, number desc);

comment on table adk_builder_srt is
    '간단한 변경을 AI가 개발요청서로 정리하는 SRT 작업. 원문 정본과 생성된 개발요청서 연결을 보존한다.';
comment on column adk_builder_srt.bridge_frd_id is
    '기존 개발요청서 검증·전송 체계를 재사용하기 위한 내부 호환 연결. FRD 화면에는 노출하지 않는다.';
