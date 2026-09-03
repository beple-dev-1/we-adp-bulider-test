alter table builder.adk_builder_dev_request
    add column development_state varchar(16),
    add column development_checked_at timestamp with time zone,
    add column development_sync_error varchar(1000),
    add column development_merged_sha varchar(64),
    add column development_merged_at timestamp with time zone;

alter table builder.adk_builder_dev_request
    add constraint adk_builder_dev_request_development_state_check
        check (development_state in ('INTAKE', 'PROGRESS', 'DONE'));

comment on column builder.adk_builder_dev_request.development_state is
    'GitLab 이슈 라벨에서 확인한 개발 상태. 전송 전에는 비어 있다.';
comment on column builder.adk_builder_dev_request.development_checked_at is
    'GitLab 개발 상태를 마지막으로 확인한 시각';
comment on column builder.adk_builder_dev_request.development_sync_error is
    '마지막 상태 확인 또는 완료 브랜치 병합 실패 이유';
comment on column builder.adk_builder_dev_request.development_merged_sha is
    '기본 브랜치에 병합한 FRD 전달 기준 커밋';
comment on column builder.adk_builder_dev_request.development_merged_at is
    '개발 완료 FRD 커밋의 기본 브랜치 반영 확인 시각';
