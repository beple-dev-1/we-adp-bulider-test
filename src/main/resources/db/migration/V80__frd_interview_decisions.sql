-- 인터뷰는 확인 필요를 남기지 않고 다 정한다 — 정한 것은 질문과 답을 함께 담는다.
-- OPEN_ISSUE 는 이미 저장된 옛 결과를 읽기 위해 남긴다. 새 결과는 만들지 않는다.

alter table adk_builder_frd_analysis_note
    drop constraint adk_builder_frd_analysis_note_kind_check;

alter table adk_builder_frd_analysis_note
    add constraint adk_builder_frd_analysis_note_kind_check
        check (kind in ('ACCEPTANCE_CRITERION', 'OPEN_ISSUE',
                        'WORK_MODE_FAST_TRACK', 'WORK_MODE_FRD',
                        'DECISION_INTERVIEW', 'DECISION_RECOMMENDED'));

comment on column adk_builder_frd_analysis_note.kind is
    '완료 기준 · 옛 확인 필요 항목 · AI가 권장한 작업 진행 방식 · 인터뷰가 정한 것(사람이 답함 DECISION_INTERVIEW, AI 권장안으로 정함 DECISION_RECOMMENDED)이다.';

comment on column adk_builder_frd_analysis_note.content is
    '항목 내용. 정한 것(DECISION_*)은 첫 줄이 질문, 둘째 줄부터 답이다.';
