create table builder.adk_builder_dev_request_test_result (
    dev_request_id  varchar(7)   not null
        references builder.adk_builder_dev_request (id),
    kind            text         not null,
    tc_id           varchar(40)  not null,
    title           text,
    actual          text,
    verdict         text         not null,
    raw_verdict     text,
    evidence        text,
    received_at     timestamptz  not null default now(),
    primary key (dev_request_id, tc_id),
    constraint adk_builder_dev_request_test_result_kind_ck
        check (kind in ('UNIT', 'INTEGRATION')),
    constraint adk_builder_dev_request_test_result_verdict_ck
        check (verdict in ('PASS', 'FAIL', 'UNKNOWN')),
    constraint adk_builder_dev_request_test_result_tc_ck
        check (length(trim(tc_id)) > 0)
);

comment on table builder.adk_builder_dev_request_test_result is
    '개발이 돌려보낸 테스트 결과. TC 번호마다 한 줄이다 — md 통째로 담으면 화면이 통과·실패를 세지 못한다.';
comment on column builder.adk_builder_dev_request_test_result.kind is
    'UNIT 화면 외 구현 항목마다, INTEGRATION 완료 조건마다. 갈래를 섞으면 개수가 서로를 오염시킨다.';
comment on column builder.adk_builder_dev_request_test_result.tc_id is
    '우리가 먼저 적어 보낸 TC 번호. 이 번호로 짝을 맞춘다 — 개발이 앞 칸을 고치면 짝이 어긋난다.';
comment on column builder.adk_builder_dev_request_test_result.verdict is
    'PASS 통과, FAIL 실패, UNKNOWN 모름. 안 채운 줄과 모르는 판정 말이 둘 다 UNKNOWN 이다.';
comment on column builder.adk_builder_dev_request_test_result.raw_verdict is
    '개발이 판정 칸에 적은 원문. 모르는 말을 통과로 세지 않고 그대로 남긴다.';
comment on column builder.adk_builder_dev_request_test_result.actual is
    '개발이 적은 실제 결과. 안 채웠으면 null 이고, 그때 verdict 는 UNKNOWN 이다.';
comment on column builder.adk_builder_dev_request_test_result.evidence is
    '개발이 댄 근거(로그 위치·캡처 따위). 뒤에 「됐다/안 됐다」를 가릴 때 본다.';
comment on column builder.adk_builder_dev_request_test_result.received_at is
    '받은 시각. 같은 개발요청서로 다시 받으면 그 줄을 갈아 끼우므로 마지막으로 받은 때다.';
