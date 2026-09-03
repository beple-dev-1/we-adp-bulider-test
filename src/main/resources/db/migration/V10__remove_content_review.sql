-- 문서 내용 분석 성공 뒤 사람 확인 단계를 없애고 곧바로 요구사항 분석이 가능하게 한다.
update adk_builder_received_document
   set document_content = coalesce(document_content, extracted_content),
       content_state = 'READY'
 where content_state = 'REVIEW_REQUIRED';

alter table adk_builder_received_document
    drop constraint adk_builder_received_document_content_state_check,
    add constraint adk_builder_received_document_content_state_check
        check (content_state in ('QUEUED', 'PROCESSING', 'READY', 'FAILED'));

comment on column adk_builder_received_document.content_state is
    '첨부파일 내용 분석 상태. QUEUED(내용 분석 대기)·PROCESSING(내용 분석 중)·READY(등록 완료)·FAILED(문서 처리 오류). 분석에 성공하면 문서 내용을 채우고 바로 READY로 옮긴다.';
