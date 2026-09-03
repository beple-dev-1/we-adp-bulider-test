create table adk_builder_frd_screen_marker_history (
    id                bigint generated always as identity primary key,
    screen_history_id bigint       not null references adk_builder_frd_screen_history (id) on delete cascade,
    marker_id         varchar(7)   not null,
    marker_no         integer      not null,
    author_account_id varchar(7)   not null,
    author_name       varchar(100) not null,
    selector          varchar(2000) not null,
    element_label     varchar(300) not null,
    relative_x        double precision not null,
    relative_y        double precision not null,
    document_x        double precision not null,
    document_y        double precision not null,
    description       varchar(4000) not null,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    constraint adk_builder_frd_screen_marker_history_position_check check (
        relative_x between 0 and 1 and relative_y between 0 and 1
        and document_x between 0 and 1 and document_y between 0 and 1
    ),
    constraint adk_builder_frd_screen_marker_history_number_unique unique (screen_history_id, marker_no)
);

create index adk_builder_frd_screen_marker_history_by_history
    on adk_builder_frd_screen_marker_history (screen_history_id, marker_no);

comment on table adk_builder_frd_screen_marker_history is
    'FRD 화면 변경 이력이 만들어질 때 함께 보존하는 실행 마커 스냅샷.';
