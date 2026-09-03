-- Flow 게시물을 받은 문서의 독립 문서 종류로 구분한다.
alter table adk_builder_received_document
    drop constraint adk_builder_received_document_document_type_check,
    add constraint adk_builder_received_document_document_type_check
        check (document_type in ('MEETING_MINUTES', 'WORK_REQUEST', 'PROPOSAL', 'FLOW', 'OTHER'));

comment on column adk_builder_received_document.document_type is
    '등록할 때 고른 문서 종류. MEETING_MINUTES(회의록)·WORK_REQUEST(과업요청서)·PROPOSAL(제안서)·FLOW(Flow 게시물)·OTHER(기타) 중 하나다.';
