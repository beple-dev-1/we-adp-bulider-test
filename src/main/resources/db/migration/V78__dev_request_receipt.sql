create table builder.adk_builder_dev_request_receipt (
    id              bigint       generated always as identity primary key,
    dev_request_id  varchar(7)   not null
        references builder.adk_builder_dev_request (id),
    returned_head   varchar(64),
    outcome         text         not null,
    receive_commit  varchar(64),
    test_rows       integer      not null default 0,
    rejections      text,
    account_id      varchar(7),
    received_at     timestamptz  not null default now(),
    constraint adk_builder_dev_request_receipt_outcome_ck
        check (outcome in ('ACCEPTED', 'REJECTED', 'ALREADY'))
);

create index adk_builder_dev_request_receipt_request_ix
    on builder.adk_builder_dev_request_receipt (dev_request_id, received_at desc);

comment on table builder.adk_builder_dev_request_receipt is
    '「개발 결과 받기」를 누를 때마다 한 줄. 무엇이 언제 어느 판으로 들어왔나를 사람이 짚는 수신 이력이다.';
comment on column builder.adk_builder_dev_request_receipt.id is
    '줄 번호. 누른 순서대로 늘어난다.';
comment on column builder.adk_builder_dev_request_receipt.dev_request_id is
    '받은 개발요청서.';
comment on column builder.adk_builder_dev_request_receipt.returned_head is
    '개발이 돌려보낸 브랜치의 머리 커밋. 돌려보낸 브랜치를 못 받았으면 비어 있다.';
comment on column builder.adk_builder_dev_request_receipt.outcome is
    'ACCEPTED 받음, REJECTED 거절, ALREADY 이미 받은 판을 다시 누름. 거절도 남긴다 — 무엇 때문에 몇 번 떨어졌나가 개발과 말을 맞출 근거다.';
comment on column builder.adk_builder_dev_request_receipt.receive_commit is
    '기본 브랜치에 놓은 받기 커밋. 거절이거나 바뀐 파일이 없었으면 비어 있다.';
comment on column builder.adk_builder_dev_request_receipt.test_rows is
    '이번에 담은 테스트 결과 줄 수. 거절이면 0이다.';
comment on column builder.adk_builder_dev_request_receipt.rejections is
    '거절 사유. 사유마다 한 줄이다.';
comment on column builder.adk_builder_dev_request_receipt.account_id is
    '받기를 누른 계정. 계정을 지워도 이력은 남기려고 외래키를 걸지 않았다.';
comment on column builder.adk_builder_dev_request_receipt.received_at is
    '누른 시각.';
