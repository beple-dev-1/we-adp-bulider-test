-- 솔루션 목업이 운영 화면과 어긋난 자리를 사람이 짚어 두는 표 (2026-08-16 · 계획 7).
--
-- 왜 지금 — 솔루션 목업 화면 둘(목업 08·08a)이 서면서 「실물과 다름」 문이 열린다.
--   설계(specs/2026-08-14-solution-mockup-correction-design.md)가 문 셋 중 이것만
--   보정 권한도 Claude 자격도 요구하지 않는 것으로 정했다 — 그래서 이것만 지금 실제로 돈다.
--
-- ⚠ 번호가 12 가 아니라 13 인 까닭 — 2026-08-16 에 main 작업트리에 다른 세션의 미커밋
--   마이그레이션 넷(V8 받은 문서 삭제 · V9 flow 문서 종류 · V10 내용 확인 걷어내기 ·
--   V12 문서 종류 단순화)이 떠 있었다. 같은 번호가 둘이면 Flyway 가 부팅에서 죽는다.
--   번호 사이가 비는 것은 Flyway 가 허용한다. ⛔ 저 넷이 안 들어와도 12 로 되돌리지 마라.
--
-- ⚠ 자료를 옮기지 않는다 — update 가 한 줄도 없다. 그래서 「옛 모양으로 줄을 앉힌 뒤
--   이것을 돌리는」 테스트(본보기 V7LegacyDataMigrationTest)가 딸려오지 않는다.

create sequence adk_builder_mockup_mismatch_seq;

create table adk_builder_mockup_mismatch (
    id          varchar(7)  primary key
                            default lpad(nextval('adk_builder_mockup_mismatch_seq')::text, 7, '0')
                            check (id ~ '^[0-9]{7}$'),
    project_id  varchar(7)  not null references adk_builder_project (id),
    screen_id   text        not null check (btrim(screen_id) <> ''),
    reason      text        not null check (btrim(reason) <> ''),
    reporter_id varchar(7)  not null references adk_builder_account (id),
    created_at  timestamptz not null default now()
);

alter sequence adk_builder_mockup_mismatch_seq owned by adk_builder_mockup_mismatch.id;

create index adk_builder_mockup_mismatch_by_screen
    on adk_builder_mockup_mismatch (project_id, screen_id);

comment on table  adk_builder_mockup_mismatch             is '솔루션 목업(③)이 운영 화면과 어긋난다고 사람이 짚어 둔 표시. ⛔ 고친 기록이 아니다 — 고치는 것은 「보정」이고 그쪽은 워크트리 → 커밋 → 푸시로 git 에 남는다. 여기는 「발견했다」만 담는다.';
comment on column adk_builder_mockup_mismatch.id          is '이 표의 기본키. 0 채운 일곱 자리 글자이고 DB 가 시퀀스로 채운다.';
comment on column adk_builder_mockup_mismatch.project_id  is '어느 프로젝트의 클론을 보고 짚었나. ⚠ 같은 화면ID 라도 프로젝트가 다르면 다른 표시다 — 클론이 저마다 다른 판을 들고 있을 수 있다.';
comment on column adk_builder_mockup_mismatch.screen_id   is '기획 저장소의 화면ID(wv-card-list 꼴). ⛔ 외래키를 걸지 마라 — 화면은 레포에 살지 DB 에 안 산다. 레포에서 화면이 사라지면 이 줄은 남고, 화면이 그것을 「없어진 화면」으로 보여준다.';
comment on column adk_builder_mockup_mismatch.reason      is '어디가 어떻게 다른가. 한 줄이다. ⛔ 비면 CHECK 가 막는다 — 까닭 없는 표시는 다음 사람이 무엇을 볼지 모른다.';
comment on column adk_builder_mockup_mismatch.reporter_id is '짚은 사람. ⚠ 보정 권한이 필요 없는 문이라 누구나 짚을 수 있다 — 그래서 누가 짚었는지가 더 중요하다.';
comment on column adk_builder_mockup_mismatch.created_at  is '짚은 때.';
