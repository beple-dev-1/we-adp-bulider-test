create table builder.adk_builder_user_manual
(
    project_id  varchar(7)   not null references builder.adk_builder_project (id),
    system_code varchar(50)  not null,
    screen_id   varchar(100) not null,
    html        text         not null,
    created_at  timestamp with time zone not null,
    primary key (project_id, system_code, screen_id)
);

comment on table builder.adk_builder_user_manual is
    '화면 하나마다 사용자 매뉴얼 한 장. 다시 만들면 덮어쓴다 — 이력을 쌓지 않는다.';
comment on column builder.adk_builder_user_manual.project_id is
    '매뉴얼이 속한 프로젝트 번호';
comment on column builder.adk_builder_user_manual.system_code is
    '클론의 시스템 폴더 이름. 화면ID 만으로는 시스템 사이에서 겹칠 수 있다';
comment on column builder.adk_builder_user_manual.screen_id is
    '기획 저장소 색인의 화면ID. 매뉴얼 한 장이 설명하는 화면이다';
comment on column builder.adk_builder_user_manual.html is
    'AI 가 화면 html·화면 md·메뉴구조도로 쓴 매뉴얼 본문(HTML)';
comment on column builder.adk_builder_user_manual.created_at is
    '이 판을 만든 시각. 덮어쓰면 새 시각으로 바뀐다';
