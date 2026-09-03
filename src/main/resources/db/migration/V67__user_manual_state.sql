-- ⛔ V66 을 고치지 않고 새로 더한다. Flyway 가 체크섬을 재므로 이미 적용된 파일을 고치면
--    「Migration checksum mismatch」로 기동 전체가 거절된다.
alter table builder.adk_builder_user_manual
    alter column html drop not null;

alter table builder.adk_builder_user_manual
    add column state varchar(16) not null default 'DONE',
    add column failed_reason varchar(1000);

alter table builder.adk_builder_user_manual
    add constraint adk_builder_user_manual_state_check
        check (state in ('RUNNING', 'DONE', 'FAILED'));

comment on column builder.adk_builder_user_manual.state is
    '만들기 상태. RUNNING=만드는 중 · DONE=작성됨 · FAILED=실패. 청한 순간부터 줄이 선다';
comment on column builder.adk_builder_user_manual.failed_reason is
    '실패한 까닭. NO_CREDENTIAL·AI_EXECUTION_FAILED·INVALID_RESPONSE 처럼 코드로 남긴다';
comment on column builder.adk_builder_user_manual.html is
    'AI 가 화면 html·화면 md 로 쓴 매뉴얼 본문(HTML). 만드는 중이거나 실패면 비어 있다';
