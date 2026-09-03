-- 이 상태 열이 생기기 전에 만들어진 개발요청서는 실제로 실패했는지, 요청조차 안 됐는지 가릴 기록이 없다.
-- 최신 화면 이력만 UNKNOWN 으로 옮겨 화면이 거짓으로 「아직 요청 안 함」이라 말하지 않게 한다.
update adk_builder_frd_screen_history h
   set tobe_document_state = 'UNKNOWN',
       tobe_document_failure = 'LEGACY_UNKNOWN',
       tobe_document_updated_at = d.updated_at
  from adk_builder_frd_screen s
  join adk_builder_dev_request d on d.frd_id = s.frd_id
 where h.frd_screen_id = s.id
   and (h.md is null or btrim(h.md) = '')
   and h.id = (select latest.id
                 from adk_builder_frd_screen_history latest
                where latest.frd_screen_id = s.id
                order by latest.created_at desc, latest.id desc
                limit 1);

comment on column adk_builder_frd_screen_history.tobe_document_state is
    '변경 예정 기능정의서 생성 상태: NOT_REQUESTED, REQUESTED, RUNNING, SUCCEEDED, FAILED, UNKNOWN';
