package com.bizplay.builder.account;

/** 사용자 목록 한 줄. 화면이 Claude 연결 상태를 계산하지 않게 여기서 채워 보낸다. */
public record AccountListView(String id, String loginId, String name, String email,
                              String role, String claudeState) {
}
