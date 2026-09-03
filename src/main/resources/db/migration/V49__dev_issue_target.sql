-- 개발요청을 GitLab 이슈로 여는 자리. 프로젝트마다 하나다.
-- 정본: docs/superpowers/plans/2026-08-24-plan-9-dev-request-delivery.md Task 10.

-- ⭐ 프로젝트마다인 까닭: 서버 한 대에 사업 여럿이 살고 사업마다 개발 조직이 다를 수 있다.
--    한 조직이면 같은 값을 넣으면 된다 — 반대로 서버 하나에 한 벌로 잡으면 못 나눠 쓴다.
create table adk_builder_dev_issue_target (
    project_id   varchar(7)   primary key references adk_builder_project (id),
    base_url     text         not null check (btrim(base_url) <> ''),
    project_path text         not null check (btrim(project_path) <> ''),
    token_cipher bytea        not null,
    token_nonce  bytea        not null,
    updated_at   timestamptz  not null default now(),
    updated_by   varchar(7)   references adk_builder_account (id)
);

comment on table adk_builder_dev_issue_target is
    '개발요청 꾸러미를 이슈로 여는 GitLab 자리. 설정이 없으면 전송이 「전송중」에 머물고 꾸러미만 남는다.';
comment on column adk_builder_dev_issue_target.base_url is
    'GitLab 주소. 예: https://gitlab.example.com — /api/v4 는 코드가 붙인다.';
comment on column adk_builder_dev_issue_target.project_path is
    '이슈를 여는 저장소. 네임스페이스를 포함한 경로이거나 숫자 ID다. ⛔ 기획 저장소가 아니라 개발 조직의 트래커다.';
comment on column adk_builder_dev_issue_target.token_cipher is
    '봉인한 GitLab 토큰. ⛔ 평문으로 두지 않는다 — 클론 토큰과 갈라 둔다(이슈 생성 권한이 따로 필요하다).';
