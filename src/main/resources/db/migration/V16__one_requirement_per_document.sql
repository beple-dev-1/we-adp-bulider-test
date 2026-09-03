-- 받은 문서 한 건을 상위 업무 요구 한 건으로 현실화한다.
-- 개편 전 다건 데이터는 보존하므로 유일 제약은 추가하지 않고 새 분석 경로에서 1:1을 보장한다.

comment on table builder.adk_builder_requirement is
    '받은 문서 한 건을 대표하는 상위 업무 요구. 새 분석은 문서당 한 건만 생성하며 세부 요구로 나누는 일은 요구사항정의서가 맡는다. 개편 전 생성된 다건 데이터는 보존한다.';

comment on column builder.adk_builder_requirement.intake_id is
    '출처가 되는 받은 문서. 새 분석에서는 받은 문서 한 건과 요구사항 한 건이 대응한다.';
