package com.bizplay.builder.intake;

import java.util.List;

/** Flow에서 읽어 온 게시물 원문 한 건. */
public record FlowPost(String postId, String title, String content, String connectUrl,
                       String projectTitle, List<Attachment> attachments, List<Remark> remarks) {

    public FlowPost {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        remarks = remarks == null ? List.of() : List.copyOf(remarks);
    }

    /** 기존 받은 문서 등록 경로가 쓰는 게시물 최소 형식. */
    public FlowPost(String postId, String title, String content, String connectUrl) {
        this(postId, title, content, connectUrl, null, List.of(), List.of());
    }

    /** Flow 게시물에 딸린 파일 또는 본문 이미지. */
    public record Attachment(String fileName, Long size, String url, String thumbnailUrl) {
    }

    /** 삭제되지 않은 Flow 댓글 한 건. */
    public record Remark(String remarkId, String author, String authorId, String content,
                         String createdAt, String avatarUrl, int replyCount, boolean system) {
    }
}
