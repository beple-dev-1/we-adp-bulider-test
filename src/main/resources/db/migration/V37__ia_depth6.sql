-- 메뉴구조도에 여섯째 뎁스를 더한다. 정본: docs/superpowers/specs/2026-08-14-screen-implementation-design.md
-- ⭐ 까닭 (2026-08-21 병주 확정): 메뉴구조도가 색인의 `상위화면`·`여는화면` 사슬까지 이어 붙여
--    트리를 세운다. 실측(planning-g2c 640장)에서 가장 깊은 것이 정확히 여섯 마디다 —
--    소비쿠폰 홈 → 약관 동의 → 신청서 → 신청 전 확인 → 신청 완료(모달 넷).
-- ⛔ depth5 까지로 두면 그 두 장이 조용히 빠진다. 억지로 마디를 합치지 않는다.
alter table adk_builder_ia_row add column depth6 varchar(255);

-- 뎁스는 중간을 비울 수 없다. 여섯째를 더했으니 앞의 셋도 같이 고친다.
alter table adk_builder_ia_row drop constraint adk_builder_ia_row_check;
alter table adk_builder_ia_row drop constraint adk_builder_ia_row_check1;
alter table adk_builder_ia_row drop constraint adk_builder_ia_row_check2;

alter table adk_builder_ia_row add constraint adk_builder_ia_row_depth2_gap_check
    check (depth2 is not null or (depth3 is null and depth4 is null and depth5 is null and depth6 is null));
alter table adk_builder_ia_row add constraint adk_builder_ia_row_depth3_gap_check
    check (depth3 is not null or (depth4 is null and depth5 is null and depth6 is null));
alter table adk_builder_ia_row add constraint adk_builder_ia_row_depth4_gap_check
    check (depth4 is not null or (depth5 is null and depth6 is null));
alter table adk_builder_ia_row add constraint adk_builder_ia_row_depth5_gap_check
    check (depth5 is not null or depth6 is null);

-- 경로 식별자도 여섯 마디까지 받는다. ⚠ 마디는 slug 이거나 화면ID 다 — 둘 다 영문·숫자·하이픈이다.
alter table adk_builder_ia_row drop constraint adk_builder_ia_row_path_key_check;
alter table adk_builder_ia_row add constraint adk_builder_ia_row_path_key_check
    check (path_key ~ '^[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+){0,5}$');

comment on column adk_builder_ia_row.depth6 is
    '여섯째 뎁스. ⚠ 3마디부터는 대개 화면이 마디가 된 것이다(상세 아래 팝업) — 메뉴 이름이 아니라 화면명이 들어온다.';
comment on column adk_builder_ia_row.path_key is
    '경로 식별자. 앞 두 마디는 색인 `경로` 의 slug, 그 뒤는 조상 화면ID 다. 최대 6마디.';
