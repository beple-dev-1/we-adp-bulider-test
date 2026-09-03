-- 개발요청 전송 시도를 한 줄씩 남긴다.
-- 정본: docs/superpowers/specs/2026-08-07-handoff-to-dev-design.md 「무엇을 남기나」.

-- ⛔ 상태 한 줄만 남기면 두 번째 시도가 첫 번째를 지운다. 첫 시도가 「전송중」인 채로
--    두 번째가 성공하면 둘 다 알아야 하는데 담을 자리가 없다 — 그래서 시도마다 한 줄이다.
create sequence adk_builder_dev_request_delivery_seq;

create table adk_builder_dev_request_delivery (
    id               varchar(7)   primary key
                                  default lpad(nextval('adk_builder_dev_request_delivery_seq')::text, 7, '0')
                                  check (id ~ '^[0-9]{7}$'),
    dev_request_id   varchar(7)   not null references adk_builder_dev_request (id),
    delivery_key     varchar(64)  not null check (btrim(delivery_key) <> ''),
    body_fingerprint varchar(64)  not null check (body_fingerprint ~ '^[0-9a-f]{64}$'),
    outcome          text         not null
                                  check (outcome in ('NOT_SENT', 'SENDING', 'SENT')),
    http_status      integer,
    response_id      varchar(255),
    failure          text,
    requested_by     varchar(7)   references adk_builder_account (id),
    started_at       timestamptz  not null default now(),
    finished_at      timestamptz
);

alter sequence adk_builder_dev_request_delivery_seq
    owned by adk_builder_dev_request_delivery.id;

comment on table adk_builder_dev_request_delivery is
    '개발요청 전송 시도 한 건. 「두 번 갔는지 아무도 모른다」는 기록이 없어서 생기는 상태다.';
comment on column adk_builder_dev_request_delivery.delivery_key is
    '이 개발요청서를 가리키는 세상에 하나뿐인 값. 다시 보내면 같은 키다 — 같은 키가 두 번 오면 재시도이지 새 요청이 아니다.';
comment on column adk_builder_dev_request_delivery.body_fingerprint is
    '보낸 꾸러미의 지문. 「받았다」가 어느 판을 받은 것인지 안 묶이면 그 사이 문서가 바뀌었을 때 무엇을 받았는지 모른다.';
comment on column adk_builder_dev_request_delivery.outcome is
    '이 시도가 끝난 뒤 개발요청서가 어느 상태로 갔나. 상태 한 줄이 아니라 시도마다 남긴다.';
comment on column adk_builder_dev_request_delivery.http_status is
    '돌아온 상태코드 그대로. 나중에 갈래 표를 고칠 때 근거가 된다.';
comment on column adk_builder_dev_request_delivery.response_id is
    '개발이 응답으로 준 식별자. 사람이 개발에 확인할 때 이 값으로 묻는다.';
comment on column adk_builder_dev_request_delivery.requested_by is
    '누가 눌렀나.';

create index adk_builder_dev_request_delivery_by_request
    on adk_builder_dev_request_delivery (dev_request_id, started_at desc);
