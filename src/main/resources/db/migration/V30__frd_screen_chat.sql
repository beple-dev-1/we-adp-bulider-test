create table adk_builder_frd_screen_chat_message (
    id            varchar(36) primary key,
    frd_id        varchar(7)  not null references adk_builder_frd (id) on delete cascade,
    frd_screen_id varchar(7)  not null references adk_builder_frd_screen (id) on delete cascade,
    sequence_no   integer     not null,
    role          varchar(8)  not null check (role in ('USER', 'AI')),
    state         varchar(10) not null check (state in ('DONE', 'RUNNING', 'FAILED')),
    content       text,
    failure       text,
    created_at    timestamptz not null default now(),
    completed_at  timestamptz,
    unique (frd_screen_id, sequence_no)
);

create index adk_builder_frd_screen_chat_by_screen
    on adk_builder_frd_screen_chat_message (frd_screen_id, sequence_no);

create unique index adk_builder_frd_screen_chat_one_running
    on adk_builder_frd_screen_chat_message (frd_id)
    where role = 'AI' and state = 'RUNNING';

comment on table adk_builder_frd_screen_chat_message is
    'FRD 작업대에서 화면별로 주고받은 AI 수정 대화와 실행 상태.';
comment on column adk_builder_frd_screen_chat_message.sequence_no is
    '한 화면의 대화 표시 순서. 사용자 요청과 그 요청의 AI 응답이 연속 번호를 갖는다.';
