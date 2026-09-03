package com.bizplay.builder.project;

public enum RepositoryUpdateState {
    RUNNING("업데이트 중"),
    SUCCEEDED("업데이트 완료"),
    FAILED("업데이트 실패");

    private final String label;

    RepositoryUpdateState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
