-- 요구사항 항목의 **성격** — 「개발이냐」를 「화면이냐」에서 갈라낸다.
--
-- 2026-08-18 네 번째 실측이 낳았다. verdict 셋만으로는 NO_SCREEN 하나에 뜻이 셋 들어간다:
--   ① 개발이 필요 없다(공지 게시)  ② 이 저장소 밖이다(고피지 지급시스템)  ③ 화면이 아닌 개발이다(배치·API)
-- 아래로 흐를 때 셋은 완전히 다르다 — ①은 BRD 를 안 낳고 ②는 다른 시스템에 낳아야 한다.
--
-- 반대 방향으로도 틀렸다: 「FAQ 삭제처리」가 SCREEN 을 받았다. AI 는 「삭제 버튼이 이미 있다」를
-- 스스로 적어 놓고도 그것을 담을 칸이 없어 화면 일로 갔다.
-- ⛔ 프롬프트가 부족한 게 아니라 값의 가짓수가 막았다 — 말로 더 시켜 고칠 자리가 아니다.

alter table adk_builder_frd_item
    add column nature text not null default 'DEVELOP'
        check (nature in ('DEVELOP', 'OPERATE', 'OUTSIDE'));

comment on column adk_builder_frd_item.nature is
    'DEVELOP(그 일을 할 기능이 없다 — 만들거나 고친다: 화면·로직·배치·API)·OPERATE(기능이 이미 있다 — 운영자가 자료·콘텐츠·설정을 바꾼다)·OUTSIDE(이 저장소의 세 시스템 밖이다). ⭐ 가르는 질문은 하나다 — 「그 기능이 이미 있나」. ⛔ 넷째 값을 더하지 마라: 「모르겠다」를 값으로 만들면 AI 가 거기로 도망친다. ⚠ 「데이터」와 「설정」을 가르지 마라 — 둘 다 OPERATE 이고, 차이는 note 에 적힌다.';

-- ⚠ 옛 줄은 전부 DEVELOP 이 된다. 그것이 옛 계약의 뜻과 정확히 같다 —
--   성격 축이 없을 때 짚힌 화면은 모두 작업 대상으로 승격됐다.
comment on column adk_builder_frd_item.verdict is
    'SCREEN(고칠 화면을 찾았다)·NO_SCREEN(화면 일이 아니다 — domains/ 에 근거가 있다)·NOT_INDEXED(화면 일인데 index.json 에 없다 = 아직 추출 안 됐다). ⭐ nature 가 DEVELOP 인 항목에만 뜻이 있다 — 「개발이냐」는 nature 가 답한다. ⛔ 넷째 값을 더하지 마라.';
