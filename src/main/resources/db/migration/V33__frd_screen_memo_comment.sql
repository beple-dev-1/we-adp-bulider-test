create sequence adk_builder_frd_screen_memo_comment_seq;

create table adk_builder_frd_screen_memo_comment (
    id                varchar(7)   primary key
                                    default lpad(nextval('adk_builder_frd_screen_memo_comment_seq')::text, 7, '0')
                                    check (id ~ '^[0-9]{7}$'),
    frd_screen_id     varchar(7)   not null references adk_builder_frd_screen (id) on delete cascade,
    author_account_id varchar(7)   not null references adk_builder_account (id),
    author_name       varchar(64)  not null check (btrim(author_name) <> ''),
    content           text         not null check (btrim(content) <> '' and length(content) <= 10000),
    created_at        timestamptz  not null
);

alter sequence adk_builder_frd_screen_memo_comment_seq
    owned by adk_builder_frd_screen_memo_comment.id;

create index adk_builder_frd_screen_memo_comment_by_screen
    on adk_builder_frd_screen_memo_comment (frd_screen_id, created_at, id);

comment on table adk_builder_frd_screen_memo_comment is
    'FRD 작업대의 화면별 댓글형 메모. 작성 순서와 작성자를 보존한다.';
comment on column adk_builder_frd_screen_memo_comment.author_account_id is
    '메모를 작성한 Builder 계정.';
comment on column adk_builder_frd_screen_memo_comment.author_name is
    '작성 당시 표시 이름. 계정 이름이 바뀌어도 과거 메모 표기를 유지한다.';
comment on column adk_builder_frd_screen_memo_comment.content is
    '사용자가 작성한 메모 내용. 10,000자 이내다.';
comment on column adk_builder_frd_screen_memo_comment.created_at is
    '메모를 작성한 때.';

comment on column adk_builder_frd_screen.memo is
    'V32 단일 메모 호환 열. 댓글형 메모는 adk_builder_frd_screen_memo_comment에 저장하며 새 코드는 사용하지 않는다.';
