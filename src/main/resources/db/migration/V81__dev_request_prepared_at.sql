-- 완료는 개발요청서의 준비(변경 예정 기능정의서 · 테스트 시나리오)까지 기다린다 (2026-09-24 사용자 확정).
-- 준비가 끝나야 목록에 보인다. 기획자는 FRD·SRT 화면에서 기다린다.

alter table adk_builder_dev_request
    add column prepared_at timestamptz;

update adk_builder_dev_request
   set prepared_at = created_at;

alter table adk_builder_dev_request
    alter column prepared_at set default now();

comment on column adk_builder_dev_request.prepared_at is
    '준비(변경 예정 기능정의서 · 테스트 시나리오)가 끝난 시각. 비어 있으면 준비 중이라 목록에 보이지 않는다 — 완료가 만든 직후 비우고, 준비가 끝나면 채운다. 실패하면 요청서를 거두고 FRD·SRT 를 완료 전으로 되돌린다.';
