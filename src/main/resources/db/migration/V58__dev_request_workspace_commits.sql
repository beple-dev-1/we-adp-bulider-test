alter table builder.adk_builder_dev_request
    add column workspace_base_sha varchar(64),
    add column workspace_head_sha varchar(64);

comment on column builder.adk_builder_dev_request.workspace_base_sha is
    'FRD 작업을 시작한 기준 커밋. 개발요청서 as-is 파일을 이 판에서 꺼낸다.';
comment on column builder.adk_builder_dev_request.workspace_head_sha is
    '개발요청 전송에 사용하는 FRD 작업트리 커밋. to-be 파일과 자산을 이 판에 고정한다.';
