-- 요구사항 낱개에 「사람이 판단한 것」을 담는 두 열을 놓는다 (2026-08-16).
--
-- 왜 지금 — 요구사항 화면 둘(목업 02·02a)이 서면서 확정·제외·내용 수정을 사람이 찍는다.
--   V7 은 review_state 만 놓았고 ① 제외한 까닭 ② 사람이 고친 때를 담을 자리가 없었다.
--
-- ⛔ V7 을 고쳐서 될 일이 아니다. 이미 나간 마이그레이션이라 손대면 Flyway 체크섬이 깨진다.
--
-- ⚠ 번호가 8 이 아니라 11 인 까닭 — 2026-08-16 에 main 작업트리에 다른 세션의 미커밋
--   마이그레이션 셋(V8 받은 문서 삭제 · V9 flow 문서 종류 · V10 내용 확인 걷어내기)이 떠 있었다.
--   같은 번호가 둘이면 Flyway 가 부팅에서 죽는다. 번호 사이가 비는 것은 Flyway 가 허용한다.
--   ⛔ 저 셋이 안 들어왔더라도 이것을 8 로 되돌리지 마라 — 이미 나간 번호가 될 수 있다.
--
-- ⚠ 자료를 옮기지 않는다 — update 가 한 줄도 없다. 그래서 「V7 까지 올리고 옛 모양으로 줄을
--   앉힌 뒤 이것을 돌리는」 테스트(본보기 V7LegacyDataMigrationTest)가 딸려오지 않는다.
--   ⛔ updated_at 을 not null default now() 로 바꾸지 마라 — 이미 앉은 요구사항 전부가
--      「방금 고쳐진 것」으로 보인다. 한 번도 안 고친 줄은 널이고, 화면이 created_at 을 대신 쓴다.

alter table adk_builder_requirement
    add column excluded_reason text,
    add column updated_at      timestamptz;

comment on column adk_builder_requirement.excluded_reason is
    '제외한 까닭. ⛔ 제외와 짝이다 — 제외인데 비거나 제외가 아닌데 차 있으면 아래 CHECK 가 막는다. 까닭을 안 받으면 목록에서 「왜 뺐나」를 아무도 모른다(ia 설계가 사유를 요구한다).';

comment on column adk_builder_requirement.updated_at is
    '사람이 내용을 마지막으로 고친 때. ⚠ 널이면 한 번도 안 고친 것이다 — 화면은 그때 created_at 을 대신 쓴다. ⛔ 검토 상태(확정·제외)를 찍은 때가 아니다: 그것은 내용이 바뀐 것이 아니다.';

-- ⭐ 제외와 사유가 한 몸이라는 것을 DB 가 지킨다.
-- ⚠ 이미 앉은 줄은 DRAFTED · 사유 널이라 (false = false) 로 통과한다 — 그래서 값을 안 옮겨도 된다.
alter table adk_builder_requirement
    add constraint adk_builder_requirement_excluded_reason_pair
    check ((review_state = 'EXCLUDED') = (btrim(coalesce(excluded_reason, '')) <> ''));
