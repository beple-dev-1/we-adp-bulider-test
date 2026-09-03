-- 프로젝트마다 어떤 시스템이 있고, 그것을 사람이 무슨 말로 부르나.
-- 이름·PK·COMMENT 규칙의 정본은 docs/data-model.md §0, 이 표는 같은 문서 §7 이다.
--
-- ⛔ 시스템 목록은 사업마다 다르다. 코드에도 yml 에도 박지 마라 — 2026-08-20 에 채번이
--    같은 실수를 했다(yml 에 셋을 적어 뒀는데 실물 레포의 시스템은 여섯이었고, 나머지 셋의
--    화면이 조용히 번호를 못 받았다). 코드 목록의 정본은 기획 저장소의 manifest.json 이고,
--    이 표는 그것을 읽어 앉힌 뒤 사람이 표시 이름만 채우는 자리다.

create table adk_builder_project_system (
    project_id   varchar(7)   not null references adk_builder_project (id) on delete cascade,
    system_code  varchar(50)  not null check (btrim(system_code) <> ''),
    display_name varchar(100) check (display_name is null or btrim(display_name) = display_name),
    primary key (project_id, system_code)
);

comment on table  adk_builder_project_system              is '그 프로젝트에 어떤 시스템이 있고 화면에 무슨 말로 뜨나. 코드는 기획 저장소 manifest.json 의 systems[].id 를 읽어 앉히고, 표시 이름은 관리자가 넣는다. ⛔ 이 표가 코드 목록의 정본이 아니다 — 정본은 레포이고 여기는 그 사본에 이름을 붙인 것이다.';
comment on column adk_builder_project_system.project_id   is '어느 프로젝트인가. 프로젝트 하나 = 기획 레포 하나다.';
comment on column adk_builder_project_system.system_code  is 'manifest.json 의 systems[].id 그대로다(backoffice·webview·online-pg·saleoffice·lspnoffice·portal 꼴). ⛔ 사람이 짓는 말이 아니라 레포가 가진 값이라 관리 화면에서 고칠 수 없다.';
comment on column adk_builder_project_system.display_name is '화면에 뜨는 한글 이름(백오피스 꼴). ⚠ 비어 있는 것이 정상이다 — 그러면 화면이 system_code 를 그대로 낸다. 빈칸을 내면 「시스템이 없는 화면」으로 보이기 때문이다.';

-- 이미 앉은 프로젝트에 첫 줄을 깔아 둔다.
-- ⚠ 이것은 한 번 옮기는 자료다. 종전에 자바 상수 세 줄(SolutionScreen.SYSTEM_LABELS)이 들고 있던
--   한글 셋이 그대로 여기로 온다 — 배포 직후 화면이 영문으로 되돌아가지 않게 하는 것이 목적이고,
--   ⛔ 이 세 줄을 「코드표」로 되살리지 마라. 다음 사업의 시스템은 여섯도 열도 될 수 있다.
-- ⚠ 어느 프로젝트에 어떤 시스템이 있나는 빌더가 이미 안다 — 채번 그룹표와 메뉴구조도 구조표에
--   시스템 코드가 앉아 있다. manifest.json 을 읽는 동기화가 뒤에 이 목록을 바로잡는다.
insert into adk_builder_project_system (project_id, system_code, display_name)
select seen.project_id,
       seen.system_code,
       case seen.system_code
           when 'backoffice' then '백오피스'
           when 'webview'    then '웹뷰'
           when 'online-pg'  then '온라인PG'
       end
  from (select distinct project_id, system_code from adk_builder_screen_id_group
        union
        select distinct project_id, system_code from adk_builder_ia_structure) seen;
