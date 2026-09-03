-- 분석 결과에서 화면별로 보여 줄 신규·수정 내용.
-- 선택 출처를 판정하는 pick_reason 과 분리한다. 사용자가 먼저 고른 화면도 AI가 정리한
-- 화면별 변경 내용을 가져야 하지만, 그 때문에 AI 선택 화면으로 바뀌면 안 된다.

alter table builder.adk_builder_frd_screen
    add column scope_change text;

comment on column builder.adk_builder_frd_screen.scope_change is
    '요구사항 때문에 이 화면에 필요한 신규·수정 내용. 분석 결과와 개발 범위 확인에서 화면별로 표시하며 선택 출처인 pick_reason 과 섞지 않는다.';
