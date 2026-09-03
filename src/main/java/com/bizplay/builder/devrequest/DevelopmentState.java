package com.bizplay.builder.devrequest;

/** GitLab 이슈 라벨에서 읽은 개발 진행 상태. 전달 상태와 섞지 않는다. */
public enum DevelopmentState {
    INTAKE("개발 접수", "status-badge--waiting", 1),
    PROGRESS("개발 진행 중", "status-badge--progress", 2),
    DONE("개발 완료", "status-badge--complete", 3);

    private final String label;
    private final String cssClass;
    private final int order;

    DevelopmentState(String label, String cssClass, int order) {
        this.label = label;
        this.cssClass = cssClass;
        this.order = order;
    }

    public String label() {
        return label;
    }

    public String cssClass() {
        return cssClass;
    }

    public boolean canAdvanceFrom(DevelopmentState current) {
        return current == null || order >= current.order;
    }
}
