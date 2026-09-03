-- 개발요청 전송 철회 (2026-08-25 병주 지시)
--
-- ⭐ 「취소」가 아니라 「철회」다. 이미 나간 것은 없던 일로 못 만든다 — 개발에게 알림이 갔고
--    읽음이 남는다. 우리가 할 수 있는 것은 「이건 무릅니다」를 그쪽 언어로 알리는 것뿐이다.
--
-- ⛔ NOT_SENT 로 되돌리지 않는다. 이슈는 살아 있는데 상태가 「대기」면 다시 눌러 두 번째
--    이슈가 열린다. 그래서 되돌리기가 아니라 새 상태다.
alter table builder.adk_builder_dev_request
    drop constraint if exists adk_builder_dev_request_delivery_state_check;

alter table builder.adk_builder_dev_request
    add constraint adk_builder_dev_request_delivery_state_check
        check (delivery_state in ('NOT_SENT', 'SENDING', 'SENT', 'WITHDRAWN'));

comment on column builder.adk_builder_dev_request.delivery_state is
    '전송 상태 — NOT_SENT 대기 · SENDING 전송중 · SENT 전송완료 · WITHDRAWN 철회함';
