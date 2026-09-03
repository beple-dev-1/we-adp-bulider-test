-- 프로젝트 — 기획 레포 하나 = 클론 대상.
-- ⚠ 이 표의 id 는 DB 밖으로 새어 나간다 — 주소(/projects/{id}/…)와 워크트리 폴더 이름이 이 값이다.
create sequence adk_builder_project_seq;

create table adk_builder_project (
    id             varchar(7)    primary key
                                 default lpad(nextval('adk_builder_project_seq')::text, 7, '0')
                                 check (id ~ '^[0-9]{7}$'),
    name           varchar(128)  not null unique,
    repo_url       varchar(1024) not null,
    default_branch varchar(128)  not null,
    sealed_token   bytea         not null,
    token_nonce    bytea         not null,
    state          varchar(16)   not null,
    failure_reason text,
    created_at     timestamptz   not null default now()
);

alter sequence adk_builder_project_seq owned by adk_builder_project.id;

comment on table  adk_builder_project                is '기획 레포 하나 = 클론 대상. 관리·프로젝트 등록이 채운다.';
comment on column adk_builder_project.id             is '이 표의 기본키. 0 채운 일곱 자리 글자이고 DB 가 시퀀스로 채운다. 사람이 보는 산출물 번호가 아니다. 주소와 워크트리 폴더 이름이 이 값이라 DB 밖으로 새어 나간다.';
comment on column adk_builder_project.name           is '프로젝트 이름. 앞뒤 빈 칸은 다듬어 저장하고 빈 이름은 거절한다. 전체에서 유일하다.';
comment on column adk_builder_project.repo_url       is '기획 레포(GitLab) 주소. 클론이 이 주소를 쓴다.';
comment on column adk_builder_project.default_branch is '클론한 뒤 체크아웃할 기본 브랜치 이름.';
comment on column adk_builder_project.sealed_token   is '봉인된 GitLab 접근 토큰. 평문으로 두지 않는다 — token_nonce 와 짝이다.';
comment on column adk_builder_project.token_nonce    is '봉인 nonce. sealed_token 과 짝으로만 뜻이 있다.';
comment on column adk_builder_project.state          is '클론 상태. RECEIVING(받는 중)·READY(준비됨)·FAILED(실패) 셋 중 하나를 이름 그대로 담는다. READY 인 것만 프로젝트 고르기에 뜬다.';
comment on column adk_builder_project.failure_reason is '클론이 실패한 까닭. FAILED 일 때만 차고, 다시 받거나 성공하면 비운다.';
comment on column adk_builder_project.created_at     is '만들어진(시작된) 때. 여기서는 프로젝트를 등록한 때다.';
