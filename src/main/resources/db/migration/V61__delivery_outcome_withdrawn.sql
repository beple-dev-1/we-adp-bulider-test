-- GitLab 철회 성공 결과도 전송 이력의 정상 종결 상태다.
-- V53은 개발요청서 본체 상태만 확장하고 이력 제약을 빠뜨려,
-- 외부 이슈를 닫은 뒤 WITHDRAWN 이력 저장에서 트랜잭션이 롤백됐다.
alter table builder.adk_builder_dev_request_delivery
    drop constraint if exists adk_builder_dev_request_delivery_outcome_check;

alter table builder.adk_builder_dev_request_delivery
    add constraint adk_builder_dev_request_delivery_outcome_check
        check (outcome in ('NOT_SENT', 'SENDING', 'SENT', 'WITHDRAWN'));

comment on column builder.adk_builder_dev_request_delivery.outcome is
    '전송 시도 결과 — NOT_SENT · SENDING · SENT · WITHDRAWN';
