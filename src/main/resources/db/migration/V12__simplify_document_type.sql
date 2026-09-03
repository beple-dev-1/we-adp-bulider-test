-- 받은 문서 종류를 Flow·회의록·기타로 단순화한다.
-- 기존 과업요청서와 제안서는 문서를 잃지 않고 기타로 통합한다.
update adk_builder_received_document
   set document_type = 'OTHER'
 where document_type in ('WORK_REQUEST', 'PROPOSAL');

alter table adk_builder_received_document
    drop constraint adk_builder_received_document_document_type_check,
    add constraint adk_builder_received_document_document_type_check
        check (document_type in ('FLOW', 'MEETING_MINUTES', 'OTHER'));

comment on column adk_builder_received_document.document_type is
    '받은 원문의 형태. FLOW(Flow)·MEETING_MINUTES(회의록)·OTHER(기타) 중 하나다.';
