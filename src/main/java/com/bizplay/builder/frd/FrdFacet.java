package com.bizplay.builder.frd;

/** FRD 요구사항을 적용할 대상 하나. */
public record FrdFacet(String frdId, String projectId, String name) {

    public static FrdFacet create(String frdId, String projectId, String name) {
        return new FrdFacet(frdId, projectId, name.strip());
    }
}
