package com.bizplay.builder.businesslanguage;

/** 정책 항목 또는 표준용어 한 건의 개정 전후 내용. */
public record BusinessDocumentChange(
        BusinessDocumentChangeType type,
        String title,
        String beforeContent,
        String afterContent
) {
}
