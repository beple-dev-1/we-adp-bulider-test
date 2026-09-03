alter table builder.adk_builder_user_manual
    add column capture_bundle_path varchar(1000),
    add column capture_file_name varchar(255),
    add column capture_label varchar(500),
    add column capture_width integer,
    add column capture_height integer,
    add column capture_sha256 varchar(64),
    add constraint adk_builder_user_manual_capture_pointer_check
        check (
            (capture_bundle_path is null
                and capture_file_name is null
                and capture_label is null
                and capture_width is null
                and capture_height is null
                and capture_sha256 is null)
            or
            (capture_bundle_path is not null
                and capture_bundle_path <> ''
                and capture_file_name is not null
                and capture_file_name <> ''
                and capture_label is not null
                and capture_label <> ''
                and capture_width is not null
                and capture_width > 0
                and capture_height is not null
                and capture_height > 0
                and capture_sha256 is not null
                and capture_sha256 ~ '^[0-9a-f]{64}$')
        );

comment on column builder.adk_builder_user_manual.capture_bundle_path is
    '마지막 정상본의 대표 화면 캡처가 든 데이터 루트 아래 불변 번들 경로';
comment on column builder.adk_builder_user_manual.capture_file_name is
    '대표 화면 캡처 파일 이름';
comment on column builder.adk_builder_user_manual.capture_label is
    '대표 화면 캡처를 설명하는 이름';
comment on column builder.adk_builder_user_manual.capture_width is
    '대표 화면 캡처의 픽셀 너비';
comment on column builder.adk_builder_user_manual.capture_height is
    '대표 화면 캡처의 픽셀 높이';
comment on column builder.adk_builder_user_manual.capture_sha256 is
    '대표 화면 캡처 파일의 SHA-256 무결성 값';
