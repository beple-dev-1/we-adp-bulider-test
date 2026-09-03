-- 클로드 자격 — 사람마다 봉인된 「claudeAiOauth 한 칸짜리 JSON 문서」 하나.
-- ⛔ 자격 파일 전체가 아니다 — 같은 파일에 사는 MCP 서버 OAuth 토큰은 여기 오지 않는다.
-- PK 가 곧 FK 라서 사람당 한 줄이다. 시퀀스가 없는 것은 그 때문이다 — 번호를 새로 나눠 주지 않는다.
-- ⛔ id 꼴 CHECK 를 여기 또 달지 않는다. FK 가 adk_builder_account.id 만 허용하고 그쪽에 이미 CHECK 가 있어
--    여기 다는 것은 발동 조건이 0 건이다.
create table adk_builder_claude_credential (
    account_id   varchar(7)  primary key references adk_builder_account (id),
    sealed_token bytea       not null,
    nonce        bytea       not null,
    connected_at timestamptz not null default now()
);

comment on table  adk_builder_claude_credential              is '사람마다 봉인된 claudeAiOauth 한 칸. 자격 파일 전체가 아니다. 「클로드 연결」 화면이 채운다.';
comment on column adk_builder_claude_credential.account_id   is '어느 사람인가. 빌더에 로그인한 계정을 가리킨다. 사람당 한 줄이라 기본키이면서 외래키다.';
comment on column adk_builder_claude_credential.sealed_token is '봉인된 claudeAiOauth 한 칸짜리 JSON. 평문으로 두지 않는다 — nonce 와 짝이다.';
comment on column adk_builder_claude_credential.nonce        is '봉인 nonce. sealed_token 과 짝으로만 뜻이 있다.';
comment on column adk_builder_claude_credential.connected_at is '만들어진(시작된) 때. 여기서는 「클로드 연결」을 마친 때다.';
