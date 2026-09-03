package com.bizplay.builder.claude;

/** 같은 Claude 계정이 다른 Builder 사용자에게 이미 연결된 경우다. */
public class ClaudeAccountAlreadyConnectedException extends RuntimeException {

    public ClaudeAccountAlreadyConnectedException() {
        super("이미 다른 사용자에게 연결된 Claude 계정입니다.");
    }
}
