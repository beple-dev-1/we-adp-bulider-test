alter table adk_builder_frd_screen_chat_message
    add column channel varchar(10) not null default 'SCREEN'
        check (channel in ('SCREEN', 'CANVAS'));

do $$
declare
    old_unique text;
begin
    select conname
      into old_unique
      from pg_constraint
     where conrelid = 'adk_builder_frd_screen_chat_message'::regclass
       and contype = 'u'
       and pg_get_constraintdef(oid) = 'UNIQUE (frd_screen_id, sequence_no)';
    if old_unique is not null then
        execute format('alter table adk_builder_frd_screen_chat_message drop constraint %I', old_unique);
    end if;
end $$;

alter table adk_builder_frd_screen_chat_message
    add constraint adk_builder_frd_chat_channel_sequence_unique
        unique (frd_screen_id, channel, sequence_no);

create index adk_builder_frd_canvas_chat_by_frd
    on adk_builder_frd_screen_chat_message (frd_id, channel, sequence_no);

comment on column adk_builder_frd_screen_chat_message.channel is
    '화면 상세 대화(SCREEN)와 전체 캔버스 대화(CANVAS)를 구분한다.';
