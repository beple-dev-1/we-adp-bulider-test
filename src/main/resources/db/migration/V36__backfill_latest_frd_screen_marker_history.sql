delete from adk_builder_frd_screen_marker_history marker_history
 using (
       select distinct on (frd_screen_id) id, frd_screen_id
         from adk_builder_frd_screen_history
        order by frd_screen_id, created_at desc, id desc
 ) latest
 where marker_history.screen_history_id = latest.id;

insert into adk_builder_frd_screen_marker_history
       (screen_history_id, marker_id, marker_no, author_account_id, author_name,
        selector, element_label, relative_x, relative_y, document_x, document_y,
        description, created_at, updated_at)
select latest.id, marker.id, marker.marker_no, marker.author_account_id, marker.author_name,
       marker.selector, marker.element_label, marker.relative_x, marker.relative_y,
       marker.document_x, marker.document_y, marker.description, marker.created_at, marker.updated_at
  from adk_builder_frd_screen_marker marker
  join lateral (
       select history.id
         from adk_builder_frd_screen_history history
        where history.frd_screen_id = marker.frd_screen_id
        order by history.created_at desc, history.id desc
        limit 1
  ) latest on true
 order by latest.id, marker.marker_no;

comment on table adk_builder_frd_screen_marker_history is
    'FRD 화면 변경 이력별 실행 마커 스냅샷. 최신 이력은 현재 화면의 마커 변경을 함께 반영한다.';
