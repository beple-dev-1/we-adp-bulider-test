package com.bizplay.builder.intake;

/** Flow 게시물을 가져오지 못했을 때 사람에게 다음 행동을 알려 주는 예외. */
public class FlowPostException extends RuntimeException {

    public FlowPostException(String message) {
        super(message);
    }

    public FlowPostException(String message, Throwable cause) {
        super(message, cause);
    }
}
