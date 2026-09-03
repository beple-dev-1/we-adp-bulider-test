create table adk_builder_frd_screen_history (
    id            bigint generated always as identity primary key,
    frd_screen_id varchar(7)  not null references adk_builder_frd_screen (id) on delete cascade,
    html          text        not null,
    changes       text,
    created_at    timestamptz not null default now()
);

create index adk_builder_frd_screen_history_by_screen
    on adk_builder_frd_screen_history (frd_screen_id, created_at desc);

comment on table adk_builder_frd_screen_history is
    'AI가 FRD 화면을 수정해 완료할 때마다 남기는 복원 가능한 화면 스냅샷.';
comment on column adk_builder_frd_screen_history.changes is
    '이 버전에서 AI가 수정했다고 보고한 내용. 화면 표시를 위해 줄바꿈으로 구분한다.';
