alter table adk_builder_srt
    add column analysis_state text not null default 'READY',
    add column analysis_message text;

alter table adk_builder_srt
    add constraint adk_builder_srt_analysis_state_check
        check (analysis_state in ('READY', 'ANALYZING', 'COMPLETE', 'REJECTED', 'FAILED'));

comment on column adk_builder_srt.analysis_state is
    'SRT 등록 원문에 대한 AI 최소 적합성 분석 상태.';
comment on column adk_builder_srt.analysis_message is
    'AI 분석 거절 또는 실패 사유.';
