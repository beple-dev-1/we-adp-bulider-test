package com.bizplay.builder.srt;

/** 플로우 원문에 딸린 첨부파일 표시 정보다. */
public record SourceAttachment(String name, String url, Long size) {
    public String displayName(int index) {
        return name == null || name.isBlank() ? "첨부파일 " + index : name;
    }
}
