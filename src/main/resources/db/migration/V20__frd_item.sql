-- 요구사항 항목마다 판정 하나 + 화면마다 시스템.
-- 2026-08-18 실측이 낳았다: 웹뷰 4건 + 지급시스템 2건짜리 요구사항이 화면 1장으로 끝났는데
-- 나머지 다섯이 아무 말 없이 사라졌다. 화면 목록만 받으면 「AI 가 못 찾은 것」과
-- 「화면 일이 아닌 것」과 「아직 추출 안 된 화면」이 구별되지 않는다.

create sequence adk_builder_frd_item_seq;

-- ⭐ 화면마다 시스템을 둔다 — 하나의 요구사항이 webview 와 backoffice 에 같이 걸리는 것이 정상이다.
--   웹뷰에 보이는 것을 백오피스에서 끄는 일이 흔하다.
--   ⚠ adk_builder_frd.system_code 는 남는다 — 짚은 화면이 한 시스템일 때만 찬다.
alter table adk_builder_frd_screen add column system_code varchar(50);

comment on column adk_builder_frd_screen.system_code is
    '이 화면이 사는 시스템(webview·backoffice·online-pg). ⛔ FRD 하나가 한 시스템이라고 보지 마라 — 웹뷰에 보이는 것을 백오피스에서 끄는 요구사항이 흔하다. 걸치면 adk_builder_frd.system_code 는 비고 이 열만 찬다.';

create table adk_builder_frd_item (
    id          varchar(7)  primary key
                            default lpad(nextval('adk_builder_frd_item_seq')::text, 7, '0')
                            check (id ~ '^[0-9]{7}$'),
    frd_id      varchar(7)  not null references adk_builder_frd (id) on delete cascade,
    seq         integer     not null check (seq > 0),
    requirement text        not null check (btrim(requirement) <> ''),
    verdict     text        not null check (verdict in ('SCREEN', 'NO_SCREEN', 'NOT_INDEXED')),
    screen_ids  text,
    note        text,
    created_at  timestamptz not null default now(),
    unique (frd_id, seq)
);

alter sequence adk_builder_frd_item_seq owned by adk_builder_frd_item.id;

comment on table  adk_builder_frd_item is
    'AI 가 요구사항을 쪼갠 항목 하나와 그 판정. ⭐ 조용한 누락을 드러내는 유일한 자리다 — 화면 목록만 받으면 무엇이 버려졌는지 아무도 모른다. ⛔ 다시 짚으면 통째로 갈아 낀다: 사람이 손보는 것이 아니라 AI 가 읽은 것의 사본이다.';
comment on column adk_builder_frd_item.seq is
    '요구사항 원문에서의 차례. ⚠ 사람이 원문과 나란히 읽는 순서라 화면도 이 순서로 낸다.';
comment on column adk_builder_frd_item.requirement is
    '요구사항 항목 원문. ⭐ AI 가 고쳐 쓴 요약이 아니라 원문 그대로여야 사람이 대조할 수 있다.';
comment on column adk_builder_frd_item.verdict is
    'SCREEN(고칠 화면을 찾았다)·NO_SCREEN(화면 일이 아니다 — domains/ 에 근거가 있다)·NOT_INDEXED(화면 일인데 index.json 에 없다 = 아직 추출 안 됐다). ⛔ 넷째 값을 더하지 마라 — 「모르겠다」를 값으로 만들면 AI 가 거기로 도망친다.';
comment on column adk_builder_frd_item.screen_ids is
    '이 항목이 가리키는 화면ID 를 쉼표로 이은 것. ⚠ 사람이 읽는 근거일 뿐이다 — 작업 단위는 adk_builder_frd_screen 이다. 조인하지 마라.';
comment on column adk_builder_frd_item.note is
    'AI 가 그렇게 본 까닭 한 문장. NO_SCREEN 이면 domains/ 의 근거가, NOT_INDEXED 면 찾던 화면ID 가 여기 앉는다.';

create index adk_builder_frd_item_by_frd on adk_builder_frd_item (frd_id);
