alter table adk_builder_frd_screen
    add column excluded_at timestamptz;

comment on column adk_builder_frd_screen.excluded_at is
    '전체 캔버스나 개발 범위 확인에서 작업 대상에서 제외한 시각. 원본 화면·대화·변경 이력은 삭제하지 않는다.';

create index adk_builder_frd_screen_active_by_frd
    on adk_builder_frd_screen (frd_id, created_at, screen_id)
    where excluded_at is null;
