alter table builder.adk_builder_claude_credential
    add column claude_email             varchar(320),
    add column claude_org_id            varchar(100),
    add column claude_org_name          varchar(200),
    add column claude_subscription_type varchar(50);

create unique index adk_builder_claude_credential_identity_unique
    on builder.adk_builder_claude_credential (lower(claude_email))
    where claude_email is not null;

comment on column builder.adk_builder_claude_credential.claude_email
    is 'Claude Code가 로그인 상태에서 확인한 계정 이메일. 표시와 동일 계정 중복 방지에 쓴다.';
comment on column builder.adk_builder_claude_credential.claude_org_id
    is 'Claude Code가 확인한 조직 식별자. 실제 실행 조직을 표시하고 확인하는 데 쓴다.';
comment on column builder.adk_builder_claude_credential.claude_org_name
    is 'Claude Code가 확인한 조직 표시 이름.';
comment on column builder.adk_builder_claude_credential.claude_subscription_type
    is 'Claude Code가 확인한 구독 종류.';
