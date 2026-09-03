package com.bizplay.builder.intake;

/** Flow 게시물 상세 API와 받은 문서 등록 사이의 경계. */
public interface FlowPostGateway {

    FlowPost get(String postId);

    /** Flow 업무번호가 정확히 같은 작업을 찾아 상세 원문을 가져온다. */
    FlowPost getByTaskNumber(String taskNumber);
}
