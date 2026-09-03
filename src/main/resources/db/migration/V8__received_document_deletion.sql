-- 요구사항 분석 전 받은 문서 삭제를 지원한다.
-- 접수 한 건을 지우면 그 접수에만 딸린 원문·적용 구분·처리 이력도 함께 지워져야 한다.

alter table adk_builder_received_document
    drop constraint adk_builder_received_document_intake_id_fkey,
    add constraint adk_builder_received_document_intake_id_fkey
        foreign key (intake_id) references adk_builder_intake (id) on delete cascade;

alter table adk_builder_intake_facet
    drop constraint adk_builder_intake_facet_intake_id_fkey,
    add constraint adk_builder_intake_facet_intake_id_fkey
        foreign key (intake_id) references adk_builder_intake (id) on delete cascade;

alter table adk_builder_requirement
    drop constraint adk_builder_requirement_intake_id_fkey,
    add constraint adk_builder_requirement_intake_id_fkey
        foreign key (intake_id) references adk_builder_intake (id) on delete cascade;

alter table adk_builder_document_processing_run
    drop constraint adk_builder_document_processing_run_document_id_fkey,
    add constraint adk_builder_document_processing_run_document_id_fkey
        foreign key (document_id) references adk_builder_received_document (id) on delete cascade;
