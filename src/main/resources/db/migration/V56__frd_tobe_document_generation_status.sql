alter table adk_builder_frd_screen_history
    add column tobe_document_state varchar(20) not null default 'NOT_REQUESTED',
    add column tobe_document_failure varchar(40),
    add column tobe_document_updated_at timestamptz;

update adk_builder_frd_screen_history
   set tobe_document_state = 'SUCCEEDED',
       tobe_document_updated_at = created_at
 where md is not null and btrim(md) <> '';

comment on column adk_builder_frd_screen_history.tobe_document_state is
    '변경 예정 기능정의서 생성 상태: NOT_REQUESTED, REQUESTED, RUNNING, SUCCEEDED, FAILED';
comment on column adk_builder_frd_screen_history.tobe_document_failure is
    '생성 실패 이유 코드. 사용자 화면에는 코드별 한국어 안내를 표시한다.';
comment on column adk_builder_frd_screen_history.tobe_document_updated_at is
    '기능정의서 생성 상태가 마지막으로 바뀐 시각';
