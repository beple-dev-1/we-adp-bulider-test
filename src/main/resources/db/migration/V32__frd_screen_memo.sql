alter table adk_builder_frd_screen
    add column memo text;

comment on column adk_builder_frd_screen.memo is
    'FRD 작업대에서 화면 위치와 관계없이 기록하는 계산 공식과 참고사항.';
