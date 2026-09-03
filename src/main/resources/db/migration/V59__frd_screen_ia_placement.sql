create table adk_builder_frd_screen_ia_placement (
    frd_screen_id        varchar(7) primary key
                         references adk_builder_frd_screen (id) on delete cascade,
    placement_mode      varchar(20) not null
                         check (placement_mode in ('MENU', 'CHILD', 'OPENER', 'UNRESOLVED')),
    structure_id        varchar(7),
    menu_path_key       text,
    anchor_screen_id    varchar(100),
    screen_kind         varchar(20) not null
                         check (screen_kind in ('SCREEN', 'POPUP', 'MODAL')),
    status              varchar(20) not null
                         check (status in ('PROPOSED', 'CONFIRMED', 'INVALID')),
    source              varchar(20) not null
                         check (source in ('USER', 'AI', 'INHERITED')),
    development_file_name varchar(120),
    updated_at          timestamptz not null default now(),
    updated_by          varchar(7),
    check (development_file_name is null
           or development_file_name ~ '^[a-z0-9][a-z0-9-]*$')
);

comment on table adk_builder_frd_screen_ia_placement is
    'FRD 신규 화면의 IA 배치 의도. 요구사항 입력부터 캔버스와 완료까지 같은 frd_screen_id로 유지한다.';
comment on column adk_builder_frd_screen_ia_placement.placement_mode is
    'MENU=메뉴 직접 배치, CHILD=화면형 상위화면, OPENER=팝업·모달 여는 화면, UNRESOLVED=위치 미정.';
comment on column adk_builder_frd_screen_ia_placement.development_file_name is
    '개발 조직이 신규 화면 파일을 만들 때 사용할 최종 파일명. tmp 화면ID와 별개다.';
