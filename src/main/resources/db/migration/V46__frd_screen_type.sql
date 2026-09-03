-- 신규 화면의 화면 유형. 사람이 「화면 추가」에서 고르는 유일한 값이다.
-- 정본: docs/superpowers/specs/2026-08-22-new-screen-id-design.md
--
-- ⚠ 기존 화면은 비어 있다 — 그쪽 유형은 기획 저장소 색인(IaScreenProfile)이 이미 안다.
--   이 열은 색인에 없는 화면, 곧 빌더가 만든 신규 화면의 몫이다.

alter table builder.adk_builder_frd_screen
    add column screen_type varchar(10);

comment on column builder.adk_builder_frd_screen.screen_type is
    '신규 화면의 유형 — 목록·상세·등록·수정·안내. 사람이 「화면 추가」에서 고른다. ⚠ 기존 화면은 비어 있다(색인이 안다). AI 가 목업을 만들 때 이 유형의 화면들을 읽어 그 시스템의 관례를 따른다.';
