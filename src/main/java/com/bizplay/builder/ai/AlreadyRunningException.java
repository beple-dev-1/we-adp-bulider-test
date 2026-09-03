package com.bizplay.builder.ai;

/**
 * 같은 일에 이미 실행이 돌고 있을 때 던진다.
 *
 * <p>⚠ <b>자바 쪽 검사만으로는 두 탭 경합을 못 막는다.</b> 마지막 방벽은
 * {@code adk_builder_ai_run_one_per_work} 부분 유일 인덱스이고, 자바는 그 제약 위반을 잡아
 * 이것으로 <b>바꿔 던진다.</b> 화면을 회색으로 만드는 것으로는 못 막는다 — 같은 사람이 두 탭에서 열 수 있다.
 */
public class AlreadyRunningException extends RuntimeException {

    public AlreadyRunningException(String message, Throwable cause) {
        super(message, cause);
    }
}
