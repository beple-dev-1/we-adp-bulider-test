alter table adk_builder_frd
    add column completed_at timestamptz;

update adk_builder_frd
   set completed_at = updated_at
 where state = 'DONE';

comment on column adk_builder_frd.completed_at is
    'FRD 상태가 최종 완료(DONE)로 전환된 시각. 검토 필요(REVIEW)로 넘어간 FRD 작업 완료 시각과는 다르다.';
