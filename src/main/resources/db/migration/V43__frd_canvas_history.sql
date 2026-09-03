alter table adk_builder_frd_screen_history
    add column md text,
    add column operation_id varchar(36),
    add column source varchar(20) not null default 'SCREEN_WORKBENCH'
        check (source in ('SCREEN_WORKBENCH', 'FRD_CANVAS_AI'));

create index adk_builder_frd_screen_history_by_operation
    on adk_builder_frd_screen_history (operation_id)
    where operation_id is not null;

comment on column adk_builder_frd_screen_history.md is
    '화면 HTML과 같은 시점의 화면 정의 MD. 캔버스에서 이동 관계를 바꾼 이력과 복원에 사용한다.';
comment on column adk_builder_frd_screen_history.operation_id is
    '맵 AI 요청 하나가 여러 화면을 바꿨을 때 그 이력을 함께 묶는 실행 ID.';
comment on column adk_builder_frd_screen_history.source is
    '화면별 작업대 또는 FRD 캔버스 AI 중 이 변경이 시작된 작업 공간.';
