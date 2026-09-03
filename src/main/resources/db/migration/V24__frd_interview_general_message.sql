-- 질문 답변이 아닌 자유 대화도 인터뷰 정본에 구분하여 저장한다.

alter table adk_builder_frd_interview_message
    drop constraint adk_builder_frd_interview_message_kind_check;

alter table adk_builder_frd_interview_message
    add constraint adk_builder_frd_interview_message_kind_check
        check (kind in ('SUMMARY', 'MESSAGE', 'QUESTION', 'ANSWER'));
