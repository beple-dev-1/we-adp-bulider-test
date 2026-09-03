-- 철회 시도는 지문이 없다 (2026-08-25 · 실물에서 터진 것을 고친다)
--
-- ⭐ body_fingerprint 는 「보낸 몸의 지문」이고 철회는 몸을 안 보낸다. 꾸러미를 굽지 않으므로
--    지문이 없는 것이 거짓이 아니라 참이다 — not null 이 틀린 제약이었다.
--
-- ⛔ 「철회 시도를 이력에 안 넣는다」로 피하지 마라. 「이 개발요청서에 무슨 일이 있었나」가
--    한 표에 모여야 나중에 짚을 수 있고, 끝나지 않은 시도 잠금도 이 표를 본다.
--
-- ⚠ 검사식(^[0-9a-f]{64}$)은 그대로 둔다 — 값이 있으면 여전히 64자 16진수여야 한다.
--    널만 허용하는 것이지 아무 글자나 받는 것이 아니다.
alter table builder.adk_builder_dev_request_delivery
    alter column body_fingerprint drop not null;

comment on column builder.adk_builder_dev_request_delivery.body_fingerprint is
    '보낸 꾸러미의 지문. 「받았다」가 어느 판을 받은 것인지 묶는다. ⚠ 철회 시도는 몸을 안 보내므로 널이다';
