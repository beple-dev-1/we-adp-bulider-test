-- 내부 코드 OTHER는 유지하고 사용자에게 설명하는 이름만 '일반 문서'로 바꾼다.
comment on column adk_builder_received_document.document_type is
    '받은 원문의 형태. FLOW(Flow)·MEETING_MINUTES(회의록)·OTHER(일반 문서) 중 하나다.';
