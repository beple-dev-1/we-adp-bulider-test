alter table adk_builder_frd_screen_chat_message
    add column session_id varchar(100);

comment on column adk_builder_frd_screen_chat_message.session_id is
    '이 응답을 만든 Claude 세션 ID. 다음 화면 대화에서 같은 세션을 이어갈 때 사용한다.';
