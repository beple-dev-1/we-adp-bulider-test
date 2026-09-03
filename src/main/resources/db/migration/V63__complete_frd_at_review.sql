update adk_builder_frd
   set completed_at = updated_at
 where state = 'REVIEW'
   and completed_at is null;

comment on column adk_builder_frd.completed_at is
    'FRD 작업을 완료해 개발요청서 검토(REVIEW)로 전환된 최초 시각. 최종 반영(DONE)까지 유지한다.';
