-- AI 실행 한 건 — 끝 다섯 갈래 중 하나로 끝난다.
-- 이름·PK·COMMENT 규칙의 정본은 docs/data-model.md §0 이다.
-- ⛔ 값은 영문이다. 옛 계획서 판본이 '돌고있음'·'그만둠' 이라 적었는데, 그 판본을 보고 쓰면 코드와 값이 갈린다.
create sequence adk_builder_ai_run_seq;

create table adk_builder_ai_run (
    id                  varchar(7)  primary key
                                    default lpad(nextval('adk_builder_ai_run_seq')::text, 7, '0')
                                    check (id ~ '^[0-9]{7}$'),
    project_id          varchar(7)  not null references adk_builder_project (id),
    account_id          varchar(7)  not null references adk_builder_account (id),
    work_key            text        not null,
    run_kind            text        not null
                                    check (run_kind in ('EXTRACT_REQUIREMENTS', 'WRITE_DEFINITION',
                                                        'DRAFT_BRD', 'WRITE_DEV_REQUEST')),
    state               text        not null
                                    check (state in ('RUNNING', 'SUCCEEDED', 'FAILED',
                                                     'TIMED_OUT', 'CANCELLED', 'CREDENTIAL_LOST')),
    checker_result      text        not null default 'NOT_RUN'
                                    check (checker_result in ('NOT_RUN', 'GREEN', 'RED')),
    instruction         text,
    work_dir            text        not null,
    developer_log       text,
    cancel_requested_at timestamptz,
    started_at          timestamptz not null default now(),
    finished_at         timestamptz
);

alter sequence adk_builder_ai_run_seq owned by adk_builder_ai_run.id;

comment on table  adk_builder_ai_run                     is 'AI 실행 한 건. 네 종류(요구사항뽑기·정의서쓰기·BRD초안·개발요청서쓰기)가 이 표 하나를 나눠 쓴다. ⛔ 「고치기」는 없다 — 산출물 사슬 재설계가 폐기했다(2026-08-14).';
comment on column adk_builder_ai_run.id                  is '이 표의 기본키. 0 채운 일곱 자리 글자이고 DB 가 시퀀스로 채운다. 사람이 보는 산출물 번호가 아니다.';
comment on column adk_builder_ai_run.project_id          is '어느 프로젝트(기획 레포)의 것인가.';
comment on column adk_builder_ai_run.account_id          is '어느 사람인가. 빌더에 로그인한 계정을 가리킨다. 이 사람의 Claude 자격으로 돈다.';
comment on column adk_builder_ai_run.work_key            is '일 하나를 가리키는 열쇠 글자. 「갈래:번호」 꼴이고 갈래는 INTAKE·BRD·MENU_STRUCTURE 셋이다. 예: BRD:0000012, MENU_STRUCTURE:webview. ⛔ 이 글자를 만드는 자리는 자바의 WorkKey.text() 하나뿐이다 — 부르는 쪽에서 이어 붙이면 잠금이 짝을 못 찾는다.';
comment on column adk_builder_ai_run.run_kind            is '무엇을 시키는 실행인가. EXTRACT_REQUIREMENTS·WRITE_DEFINITION·DRAFT_BRD·WRITE_DEV_REQUEST 넷이다. ⛔ 새 종류가 생겨도 장치를 새로 만들지 않는다 — 이 값만 는다.';
comment on column adk_builder_ai_run.state               is '실행 상태. RUNNING·SUCCEEDED·FAILED·TIMED_OUT·CANCELLED·CREDENTIAL_LOST 여섯 중 하나이고 그중 끝은 다섯이다. ⛔ 「한도」(RATE_LIMITED)를 값으로 더하지 않는다 — 한도 초과를 갈라낼 수 있는지 아직 못 쟀고 429 는 추정이다.';
comment on column adk_builder_ai_run.checker_result      is '규격 검사기 결과. NOT_RUN·GREEN·RED 셋. 이 회차는 언제나 NOT_RUN 을 넣고, 계획 3 이 GREEN·RED 를 넣기 시작한다. ⚠ 값과 열을 지금 만들어 둬야 계획 3 이 시그니처·엔티티·마이그레이션을 다시 안 뜯는다.';
comment on column adk_builder_ai_run.instruction         is '사람이 보낸 지시. 일꾼이 다른 스레드에서 이것을 읽어 claude 에 넘긴다 — 메모리에 두면 재기동 뒤에 무엇을 시켰는지가 사라진다.';
comment on column adk_builder_ai_run.work_dir            is '이 실행이 파일을 만지는 자리(워크트리). 자리 글자를 만드는 곳은 자바 한 곳이다.';
comment on column adk_builder_ai_run.developer_log       is '실패했을 때 개발자가 보는 원문. ⛔ 이 값을 화면에 그대로 내지 마라 — 사람에게 하는 말은 상태에서 따로 만든다.';
comment on column adk_builder_ai_run.cancel_requested_at is '사람이 그만두기를 누른 때. ⛔ 메모리에만 두면 프로세스가 뜨기 전에 누른 취소가 사라진다 — 일꾼이 프로세스를 띄우기 직전과 직후에 이 값을 본다.';
comment on column adk_builder_ai_run.started_at          is '만들어진(시작된) 때.';
comment on column adk_builder_ai_run.finished_at         is '끝난 때. 아직 안 끝났으면 비어 있다.';

-- 한 일에 「RUNNING」인 실행은 최대 하나. 화면이 아니라 여기서 막힌다.
-- ⛔ project_id 를 빼지 마라 — 번호는 프로젝트마다 1번부터라(data-model §4) 빼면 다른 사업 담당끼리 서로를 막는다.
-- ⛔ work_key 에 NULL 을 허용하지 마라 — PostgreSQL 의 유일 인덱스는 NULL 을 여러 건 허용해서 「한 일에 하나」가 조용히 깨진다.
create unique index adk_builder_ai_run_one_per_work
    on adk_builder_ai_run (project_id, work_key) where state = 'RUNNING';
