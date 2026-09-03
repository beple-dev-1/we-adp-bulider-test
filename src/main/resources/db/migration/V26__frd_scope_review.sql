alter table adk_builder_frd drop constraint adk_builder_frd_state_check;
alter table adk_builder_frd
    add constraint adk_builder_frd_state_check
        check (state in ('ANALYZING', 'WAITING_ANSWER', 'ANALYSIS_FAILED', 'PICKED',
                         'SCOPE_REVIEW', 'DRAFTING', 'REVIEW', 'DONE'));

comment on column adk_builder_frd.state is
    'ANALYZING 요구사항 분석 중, WAITING_ANSWER 답변 필요, ANALYSIS_FAILED 분석 오류, PICKED 분석 결과 확인, SCOPE_REVIEW 개발 범위 확인, DRAFTING 수정 중, REVIEW 검토 필요, DONE 완료';
