package com.bizplay.builder.project;

public enum ProjectState {
    /** 뒤에서 클론이 도는 중. 기획자에게 아직 안 열린다. */
    RECEIVING("받는 중"),
    /** 클론이 끝났다. 기획자가 고를 수 있다. */
    READY("준비됨"),
    /** 클론이 실패했다. 다시 시도할 수 있다. */
    FAILED("실패");

    private final String label;

    ProjectState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
