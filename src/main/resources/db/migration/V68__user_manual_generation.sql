alter table builder.adk_builder_user_manual
    drop constraint adk_builder_user_manual_state_check;

alter table builder.adk_builder_user_manual
    rename column state to generation_state;

alter table builder.adk_builder_user_manual
    alter column created_at drop not null,
    add column generation_id varchar(100),
    add column generation_started_at timestamp with time zone,
    add column source_fingerprint varchar(128),
    add column generator_version varchar(100);

-- V67은 결과가 없는 생성 시각도 created_at에 기록했다. 정상 HTML이 없으면 작성시각도 없는 것이 맞다.
update builder.adk_builder_user_manual
   set created_at = null
 where html is null;

-- V67의 DONE 빈 결과와 이유 없는 FAILED를 새 불변식에 맞춰 복구한다.
update builder.adk_builder_user_manual
   set generation_state = 'FAILED',
       failed_reason = coalesce(failed_reason, 'LEGACY_EMPTY_RESULT')
 where generation_state = 'DONE'
   and html is null;

update builder.adk_builder_user_manual
   set failed_reason = 'LEGACY_FAILURE'
 where generation_state = 'FAILED'
   and failed_reason is null;

update builder.adk_builder_user_manual
   set generation_id = concat('legacy-', md5(project_id || ':' || system_code || ':' || screen_id)),
       generation_started_at = coalesce(created_at, now()),
       generator_version = 'legacy-v67';

alter table builder.adk_builder_user_manual
    alter column generation_id set not null,
    alter column generation_started_at set not null,
    add constraint adk_builder_user_manual_generation_state_check
        check (generation_state in ('RUNNING', 'DONE', 'FAILED')),
    add constraint adk_builder_user_manual_result_pair_check
        check ((html is null) = (created_at is null)),
    add constraint adk_builder_user_manual_generation_invariant_check
        check (
            (generation_state = 'RUNNING' and failed_reason is null)
            or (generation_state = 'DONE' and html is not null and failed_reason is null)
            or (generation_state = 'FAILED' and failed_reason is not null)
        );

comment on column builder.adk_builder_user_manual.html is
    '마지막으로 정상 생성된 사용자 매뉴얼 HTML. 재생성 중이거나 실패해도 보존한다';
comment on column builder.adk_builder_user_manual.created_at is
    '마지막 정상 생성 시각. 정상본이 아직 없으면 비어 있다';
comment on column builder.adk_builder_user_manual.generation_state is
    '현재 생성 시도 상태. RUNNING=생성 중, DONE=완료, FAILED=실패';
comment on column builder.adk_builder_user_manual.generation_id is
    '현재 또는 가장 최근 생성 시도 식별자. 늦게 끝난 이전 작업의 역전을 막는다';
comment on column builder.adk_builder_user_manual.generation_started_at is
    '현재 또는 가장 최근 생성 시도 시작 시각. 오래 멈춘 RUNNING 선점을 회복하는 기준이다';
comment on column builder.adk_builder_user_manual.failed_reason is
    '가장 최근 생성 시도의 실패 이유. 정상본과 별도로 관리한다';
comment on column builder.adk_builder_user_manual.source_fingerprint is
    '마지막 정상본을 만든 입력 자료의 지문';
comment on column builder.adk_builder_user_manual.generator_version is
    '마지막 정상본을 만든 생성기 버전';
