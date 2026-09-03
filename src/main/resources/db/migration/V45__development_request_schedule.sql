alter table adk_builder_dev_request
    add column development_completed_on date,
    add column deployment_on date,
    add constraint dev_request_deployment_after_completion
        check (development_completed_on is null or deployment_on is null
            or deployment_on >= development_completed_on);

comment on column adk_builder_dev_request.development_completed_on is
    '개발요청 전송 시 기획자가 요청한 개발 완료일이다.';
comment on column adk_builder_dev_request.deployment_on is
    '개발요청 전송 시 기획자가 요청한 운영 배포일이다.';
