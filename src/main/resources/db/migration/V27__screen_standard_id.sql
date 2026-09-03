-- 표준 화면ID 매핑표. 정본: docs/superpowers/specs/2026-08-20-screen-standard-id-design.md
-- ⛔ 화면의 열쇠는 여전히 기획 레포의 screen_id 다. 이 표의 standard_id 는 표시용 라벨이고
--    빌더 코드가 이것으로 화면을 찾지 않는다.

create sequence adk_builder_screen_standard_id_seq;
create sequence adk_builder_screen_id_group_seq;

create table adk_builder_screen_standard_id (
    id          varchar(7)   primary key
                             default lpad(nextval('adk_builder_screen_standard_id_seq')::text, 7, '0')
                             check (id ~ '^[0-9]{7}$'),
    project_id  varchar(7)   not null references adk_builder_project (id) on delete cascade,
    screen_id   varchar(100) not null check (btrim(screen_id) <> ''),
    standard_id varchar(64)  not null check (btrim(standard_id) <> ''),
    origin      text         not null check (origin in ('S', 'N')),
    sort_no     integer      not null check (sort_no > 0),
    created_at  timestamptz  not null default now(),
    unique (project_id, screen_id),
    unique (project_id, standard_id)
);

alter sequence adk_builder_screen_standard_id_seq owned by adk_builder_screen_standard_id.id;

comment on table adk_builder_screen_standard_id is
    '기획 레포의 화면ID 에 붙인 표준 화면ID 라벨. ⛔ 대체가 아니라 매핑이다 — 파일 경로·동시편집 잠금·FRD 대상화면은 여전히 screen_id 로 돈다. 한번 박히면 IA 가 바뀌어도 안 바꾼다.';
comment on column adk_builder_screen_standard_id.screen_id is
    '기획 레포의 화면ID(bo-usag-list 꼴). 이것이 열쇠다.';
comment on column adk_builder_screen_standard_id.standard_id is
    '상태 마디를 뺀 5마디(PS-BO-MRC-010-L01). ⛔ 상태 마디(S·N·C)는 저장하지 않는다 — 볼 때 조립한다.';
comment on column adk_builder_screen_standard_id.origin is
    'S(클론 색인에서 왔다)·N(빌더에서 새로 났다). 화면 속성이라 불변이다.';
comment on column adk_builder_screen_standard_id.sort_no is
    '정렬용 순번. ⛔ standard_id 문자열로 정렬하지 마라 — 일련번호가 3자리로 늘면 뒤집힌다.';

create table adk_builder_screen_id_group (
    id           varchar(7)   primary key
                              default lpad(nextval('adk_builder_screen_id_group_seq')::text, 7, '0')
                              check (id ~ '^[0-9]{7}$'),
    project_id   varchar(7)   not null references adk_builder_project (id) on delete cascade,
    system_code  varchar(50)  not null check (btrim(system_code) <> ''),
    area_key     varchar(100) not null check (btrim(area_key) <> ''),
    area_code    varchar(3)   not null check (area_code ~ '^[A-Z]{3}$'),
    area_label   varchar(200),
    group_key    varchar(100) not null default '',
    group_no     integer      not null check (group_no >= 0),
    group_label  varchar(200),
    created_at   timestamptz  not null default now(),
    unique (project_id, system_code, area_key, group_key)
);

-- AI 가 두 업무영역에 같은 3글자를 내면 여기서 시끄럽게 깨진다 — 조용히 겹치는 것보다 낫다.
-- ⚠ XXX 는 뺀다. 「못 지었다」는 표시라서 여럿이 정상이다.
-- ⚠ 이 문은 system_code 별로 걸린다(2026-08-20 재확인). area_code 는 시스템을 넘어 같은
--    업무영역이면 같이 쓰는 게 맞다 — merchant 는 백오피스로 봐도 웹뷰로 봐도 같은 업무영역이고,
--    표준 ID 는 이미 시스템 마디(PS-BO-MRC-010 vs PS-WV-MRC-010)로 둘을 가른다. system_code 를
--    빼면 두 시스템의 group_no 가 우연히 겹칠 때(둘 다 경로 한 마디뿐이라 둘 다 0 이 되는 경우 등)
--    무관한 충돌로 assign() 이 통째로 깨진다.
create unique index adk_builder_screen_id_group_code_uk
    on adk_builder_screen_id_group (project_id, system_code, area_code, group_no)
    where area_code <> 'XXX';

alter sequence adk_builder_screen_id_group_seq owned by adk_builder_screen_id_group.id;

comment on table adk_builder_screen_id_group is
    '표준 화면ID 의 업무영역 3글자와 기능그룹 번호. ⛔ 열쇠는 IA 경로의 영문 slug 이지 한글 이름이 아니다 — 이름표는 기획자가 IA 작업대에서 고칠 수 있어 열쇠가 못 된다.';
comment on column adk_builder_screen_id_group.area_key is
    'IA 경로의 첫 마디 slug(merchant). 안정적인 경로 식별자라 이것이 열쇠다.';
comment on column adk_builder_screen_id_group.area_code is
    'AI 가 1회 지은 대문자 3글자(MRC). 못 지었으면 XXX 다. 사람이 고쳐도 이미 박힌 표준 ID 는 안 바뀐다.';
comment on column adk_builder_screen_id_group.group_key is
    'IA 경로의 둘째 마디 slug. 마디가 하나뿐인 가지는 빈 문자열이다.';
comment on column adk_builder_screen_id_group.group_no is
    '기능그룹 3자리 번호. group_key 가 비면 0 이다.';
