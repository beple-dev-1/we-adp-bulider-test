-- 계정 — 빌더에 로그인하는 사람.
-- 이름·PK·COMMENT 규칙의 정본은 docs/data-model.md §0 이다.
-- ⛔ PK 는 숫자가 아니라 0 채운 일곱 자리 글자다. 폭이 섞이면 문자 비교라서 '9' > '10' 으로 정렬이 뒤집힌다.
--    시퀀스 하나를 DB 쪽 DEFAULT 와 앱 쪽 채번(select nextval → %07d)이 함께 본다 — 같은 시퀀스라 충돌하지 않는다.
create sequence adk_builder_account_seq;

create table adk_builder_account (
    id                   varchar(7)   primary key
                                      default lpad(nextval('adk_builder_account_seq')::text, 7, '0')
                                      check (id ~ '^[0-9]{7}$'),
    login_id             varchar(64)  not null unique,
    name                 varchar(64)  not null,
    email                varchar(255) not null,
    password_hash        varchar(100) not null,
    super_account        boolean      not null default false,
    must_change_password boolean      not null default true,
    created_at           timestamptz  not null default now()
);

alter sequence adk_builder_account_seq owned by adk_builder_account.id;

comment on table  adk_builder_account                      is '빌더에 로그인하는 사람 한 줄. 슈퍼계정도 여기 산다. 부팅 시더(슈퍼)와 관리 화면이 채운다.';
comment on column adk_builder_account.id                   is '이 표의 기본키. 0 채운 일곱 자리 글자이고 DB 가 시퀀스로 채운다. 사람이 보는 산출물 번호가 아니다.';
comment on column adk_builder_account.login_id             is '로그인할 때 치는 ID. 이메일이 아니다. 전체에서 유일하다.';
comment on column adk_builder_account.name                 is '화면에 뜨는 표시 이름.';
comment on column adk_builder_account.email                is '연락용 주소. 로그인에는 쓰지 않는다.';
comment on column adk_builder_account.password_hash        is '비밀번호 해시. 평문은 담지 않는다.';
comment on column adk_builder_account.super_account        is '참이면 관리 화면에 들어갈 수 있다. 기획자는 거짓이다.';
comment on column adk_builder_account.must_change_password is '참이면 다음 로그인에서 비밀번호 변경 화면으로 강제로 보낸다. 계정을 새로 만들면 참으로 난다.';
comment on column adk_builder_account.created_at           is '만들어진(시작된) 때.';
