alter table adk_builder_dev_request
    add column attachment_name varchar(255),
    add column attachment_path text,
    add column attachment_size bigint check (attachment_size >= 0);

comment on column adk_builder_dev_request.attachment_name is
    '개발요청 전송 시 개발팀에 함께 보내는 첨부파일의 원본 이름이다.';
comment on column adk_builder_dev_request.attachment_path is
    '개발요청 전송 전까지 Builder 서버에 보관하는 첨부파일 경로다.';
comment on column adk_builder_dev_request.attachment_size is
    '개발요청 전송 첨부파일의 바이트 크기다.';
