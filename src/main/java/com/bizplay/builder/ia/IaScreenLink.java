package com.bizplay.builder.ia;

/** 솔루션 목업 한 장에 붙여 보여 줄 현재 IA 연결. */
public record IaScreenLink(
        String systemCode,
        String screenId,
        String path,
        int revision,
        IaStructure.State state) {
}
