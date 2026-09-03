-- 개발요청 전송 꾸러미가 계약으로 서기 위해 필요한 두 칸.
-- 정본: docs/superpowers/specs/2026-08-22-dev-request-package-design.md

-- ⛔ not null 로 두지 않는다. 이미 있는 FRD 의 백엔드 항목에는 값이 없고,
--    막으면 그 FRD 들이 통째로 열리지 않는다.
alter table adk_builder_frd_backend_change
    add column verification text;

comment on column adk_builder_frd_backend_change.verification is
    '이 백엔드 변경을 무엇으로 됐다고 판정하나. 화면은 목업이 그 노릇을 하지만 화면 외 구현은 이 칸이 없으면 항목별 검수가 갈리지 않는다.';

-- ⛔ 자동으로 채우지 않는다. 화면이 겹치는 것과 같은 업무인 것은 다르므로
--    빌더는 후보만 대고 사람이 고른다.
alter table adk_builder_dev_request
    add column previous_request_id varchar(7)
        references adk_builder_dev_request (id);

alter table adk_builder_dev_request
    add constraint dev_request_previous_is_not_self
        check (previous_request_id is null or previous_request_id <> id);

comment on column adk_builder_dev_request.previous_request_id is
    '같은 업무를 앞서 넘긴 개발요청서. 전체를 보내지 않으므로 개발이 앞것과 이어 읽어야 한다. 첫 요청이면 비어 있는 것이 정상이다.';

create index adk_builder_dev_request_by_previous
    on adk_builder_dev_request (previous_request_id)
    where previous_request_id is not null;
