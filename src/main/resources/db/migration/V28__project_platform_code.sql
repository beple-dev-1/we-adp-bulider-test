-- 표준 화면ID 의 첫 마디(PS-WV-MRC-010-L01-S 의 PS). 정본:
-- docs/superpowers/specs/2026-08-20-screen-standard-id-design.md §2.
-- ⚠ not null default 'PS' 로 두는 것은 기존 행을 살리기 위해서다 — 이미 앉은 프로젝트는
--   전부 PS 였던 것으로 친다(2026-08-20 이전에는 yml 의 builder.screen-id.platform 이 PS 였다).
alter table builder.adk_builder_project
    add column platform_code varchar(4) not null default 'PS'
        check (platform_code ~ '^[A-Z0-9]{2,4}$');

comment on column builder.adk_builder_project.platform_code is
    '표준 화면ID 의 첫 마디(PS 꼴). 등록할 때 사람이 정하고 그 뒤로 안 바뀐다 — 클론이 앉으면 바로 채번이 도는데, 이미 박힌 표준 ID 는 안 바꾸는 규칙이라 나중에 고쳐도 이미 번호 받은 화면엔 안 먹기 때문이다.';
