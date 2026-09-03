package com.bizplay.builder.businesslanguage;

/** 표준용어 문서의 한 행. */
public record StandardTerm(String term, String meaning, String aliases, String avoid) {
}
