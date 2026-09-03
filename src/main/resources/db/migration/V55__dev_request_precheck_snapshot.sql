-- 전송 시점의 「전송 전 확인」 결과를 얼린다 (2026-08-25 병주 지시 · 실물에서 발견)
--
-- ⭐ 전송완료된 DR-011 의 「전송 전 확인」이 상세를 열 때마다 달라졌다. 상세가 매번 지금 클론·워크트리를
--    다시 재고 있었고, 검사기 UNKNOWN 결과가 10분마다 만료돼 「점검 중 ↔ 돌리지 못했다」를 왕복했다.
--    「전송 전 확인」은 계약 시점의 기록이어야 한다 — 보낼 때 잰 결과를 여기 두고, 보낸 뒤에는 이것만 읽는다.
--
-- ⚠ NOT_SENT · WITHDRAWN 에서는 안 읽는다 — 고쳐서 다시 보낼 것은 지금 상태를 다시 재야 한다.
alter table builder.adk_builder_dev_request
    add column precheck_json text;

comment on column builder.adk_builder_dev_request.precheck_json is
    '전송 시점의 전송 전 확인 결과(JSON · DevRequestPrecheck.Result). 보낸 뒤 상세는 이것만 읽는다. 안 보낸 것은 널';
