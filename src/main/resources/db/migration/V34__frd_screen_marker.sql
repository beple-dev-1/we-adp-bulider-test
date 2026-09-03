create sequence adk_builder_frd_screen_marker_seq;

create table adk_builder_frd_screen_marker (
    id                varchar(7)       primary key
                                        default lpad(nextval('adk_builder_frd_screen_marker_seq')::text, 7, '0')
                                        check (id ~ '^[0-9]{7}$'),
    frd_screen_id     varchar(7)       not null references adk_builder_frd_screen (id) on delete cascade,
    marker_no         integer          not null check (marker_no > 0),
    author_account_id varchar(7)       not null references adk_builder_account (id),
    author_name       varchar(64)      not null check (btrim(author_name) <> ''),
    selector          varchar(2000)    not null check (btrim(selector) <> ''),
    element_label     varchar(300)     not null check (btrim(element_label) <> ''),
    relative_x        double precision not null check (relative_x between 0 and 1),
    relative_y        double precision not null check (relative_y between 0 and 1),
    document_x        double precision not null check (document_x between 0 and 1),
    document_y        double precision not null check (document_y between 0 and 1),
    description       text             not null check (btrim(description) <> '' and length(description) <= 4000),
    created_at        timestamptz      not null,
    updated_at        timestamptz      not null,
    unique (frd_screen_id, marker_no)
);

alter sequence adk_builder_frd_screen_marker_seq owned by adk_builder_frd_screen_marker.id;

create index adk_builder_frd_screen_marker_by_screen
    on adk_builder_frd_screen_marker (frd_screen_id, marker_no);

comment on table adk_builder_frd_screen_marker is
    'FRD 화면 요소에 연결한 실행 마커. 설명과 작성자 및 반응형 위치를 보존한다.';
comment on column adk_builder_frd_screen_marker.marker_no is '화면 안에서 사용자가 보는 마커 번호.';
comment on column adk_builder_frd_screen_marker.selector is '마커를 연결한 DOM 요소의 CSS 선택자.';
comment on column adk_builder_frd_screen_marker.element_label is '마커를 연결할 당시 화면 요소를 설명하는 문구.';
comment on column adk_builder_frd_screen_marker.relative_x is '연결 요소 너비에 대한 마커 가로 위치 비율.';
comment on column adk_builder_frd_screen_marker.relative_y is '연결 요소 높이에 대한 마커 세로 위치 비율.';
comment on column adk_builder_frd_screen_marker.document_x is '요소를 찾지 못할 때 사용할 문서 너비 기준 가로 비율.';
comment on column adk_builder_frd_screen_marker.document_y is '요소를 찾지 못할 때 사용할 문서 높이 기준 세로 비율.';
comment on column adk_builder_frd_screen_marker.description is '사용자가 기록한 실행 마커 설명. 4,000자 이내다.';
comment on column adk_builder_frd_screen_marker.author_account_id is '실행 마커를 처음 작성한 Builder 계정.';
comment on column adk_builder_frd_screen_marker.author_name is '작성 당시 표시 이름.';
comment on column adk_builder_frd_screen_marker.created_at is '실행 마커를 처음 작성한 때.';
comment on column adk_builder_frd_screen_marker.updated_at is '실행 마커 설명을 마지막으로 수정한 때.';
