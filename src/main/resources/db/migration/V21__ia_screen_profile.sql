-- 추출기 index/4의 화면 분류를 최초 한 번 보존한다.

create table adk_builder_ia_screen_profile (
    structure_id  varchar(7)   not null references adk_builder_ia_structure (id) on delete cascade,
    screen_id     varchar(100) not null check (btrim(screen_id) <> ''),
    screen_kind   text         not null check (screen_kind in ('SCREEN', 'POPUP', 'MODAL')),
    screen_type   text         not null check (screen_type in ('LIST', 'DETAIL', 'CREATE', 'EDIT', 'GUIDE', 'UNCLASSIFIED')),
    type_source   text         check (type_source in ('ID', 'NAME')),
    imported_at   timestamptz  not null default now(),
    primary key (structure_id, screen_id)
);

comment on table adk_builder_ia_screen_profile is
    'index.json에서 최초 한 번 가져온 화면 종류·화면 유형·판정 근거. 이후 색인으로 덮어쓰지 않는다.';
comment on column adk_builder_ia_screen_profile.screen_kind is
    '추출기 종류의 Builder 저장값: SCREEN·POPUP·MODAL.';
comment on column adk_builder_ia_screen_profile.screen_type is
    '추출기 화면유형의 Builder 저장값: LIST·DETAIL·CREATE·EDIT·GUIDE·UNCLASSIFIED.';
comment on column adk_builder_ia_screen_profile.type_source is
    '화면유형 판정 근거. ID·NAME이며 미분류는 null이다.';
