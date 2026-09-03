-- 화면 이름을 마지막 트리 마디로 보존하기 위해 일곱째 뎁스를 더한다.
-- 기존 조립은 현재 화면 이름을 빼서 상세 화면 ID가 부모 목록 이름에 연결되는 한 단계 밀림이 있었다.
alter table adk_builder_ia_row add column depth7 varchar(255);

alter table adk_builder_ia_row drop constraint adk_builder_ia_row_depth2_gap_check;
alter table adk_builder_ia_row drop constraint adk_builder_ia_row_depth3_gap_check;
alter table adk_builder_ia_row drop constraint adk_builder_ia_row_depth4_gap_check;
alter table adk_builder_ia_row drop constraint adk_builder_ia_row_depth5_gap_check;

alter table adk_builder_ia_row add constraint adk_builder_ia_row_depth2_gap_check
    check (depth2 is not null or (depth3 is null and depth4 is null and depth5 is null and depth6 is null and depth7 is null));
alter table adk_builder_ia_row add constraint adk_builder_ia_row_depth3_gap_check
    check (depth3 is not null or (depth4 is null and depth5 is null and depth6 is null and depth7 is null));
alter table adk_builder_ia_row add constraint adk_builder_ia_row_depth4_gap_check
    check (depth4 is not null or (depth5 is null and depth6 is null and depth7 is null));
alter table adk_builder_ia_row add constraint adk_builder_ia_row_depth5_gap_check
    check (depth5 is not null or (depth6 is null and depth7 is null));
alter table adk_builder_ia_row add constraint adk_builder_ia_row_depth6_gap_check
    check (depth6 is not null or depth7 is null);

alter table adk_builder_ia_row drop constraint adk_builder_ia_row_path_key_check;
alter table adk_builder_ia_row add constraint adk_builder_ia_row_path_key_check
    check (path_key ~ '^[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+){0,6}$');

comment on column adk_builder_ia_row.depth7 is
    '일곱째 뎁스. 현재 화면 이름까지 트리의 마지막 마디로 보존한다.';
comment on column adk_builder_ia_row.path_key is
    '경로 식별자. 색인 경로와 조상·현재 화면ID를 이어 붙인 최대 7마디.';
