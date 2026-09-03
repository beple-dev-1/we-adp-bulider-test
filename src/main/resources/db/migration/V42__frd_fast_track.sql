-- 간단 변경은 FRD 작업대와 워크트리를 건너뛰고 분석 결과로 개발요청서를 만들 수 있다.

alter table adk_builder_frd_analysis_note
    drop constraint adk_builder_frd_analysis_note_kind_check;

alter table adk_builder_frd_analysis_note
    add constraint adk_builder_frd_analysis_note_kind_check
        check (kind in ('ACCEPTANCE_CRITERION', 'OPEN_ISSUE',
                        'WORK_MODE_FAST_TRACK', 'WORK_MODE_FRD'));

comment on column adk_builder_frd_analysis_note.kind is
    '완료 기준, 확인 필요 항목 또는 AI가 권장한 작업 진행 방식이다.';
