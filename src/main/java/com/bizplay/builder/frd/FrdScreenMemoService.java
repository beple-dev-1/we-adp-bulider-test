package com.bizplay.builder.frd;

import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.id.IdSequence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 화면별 댓글형 메모를 작성 순서대로 관리한다. */
@Service
public class FrdScreenMemoService {

    static final int MAX_LENGTH = 10_000;

    private final FrdScreenMemoCommentMapper comments;
    private final IdSequence ids;

    public FrdScreenMemoService(FrdScreenMemoCommentMapper comments, IdSequence ids) {
        this.comments = comments;
        this.ids = ids;
    }

    public MemoThread read(FrdScreen screen) {
        List<MemoComment> rows = comments.selectByScreenId(screen.id()).stream()
                .map(FrdScreenMemoService::viewOf)
                .toList();
        return new MemoThread(screen.screenId(), displayName(screen), rows);
    }

    @Transactional
    public MemoComment add(FrdScreen screen, BuilderUser author, String memo) {
        String content = value(memo);
        if (content.isBlank()) {
            throw new IllegalArgumentException("메모 내용을 입력해 주세요.");
        }
        if (content.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("메모는 10,000자 이내로 입력해 주세요.");
        }
        FrdScreenMemoComment comment = new FrdScreenMemoComment(
                ids.next(IdSequence.Kind.FRD_SCREEN_MEMO_COMMENT), screen.id(), author.accountId(),
                author.name(), content, Instant.now());
        comments.insert(comment);
        return viewOf(comment);
    }

    private static String value(String memo) {
        return memo == null ? "" : memo.strip();
    }

    private static String displayName(FrdScreen screen) {
        return screen.screenName() == null || screen.screenName().isBlank()
                ? screen.screenId() : screen.screenName();
    }

    private static MemoComment viewOf(FrdScreenMemoComment comment) {
        return new MemoComment(comment.id(), comment.authorName(), comment.content(), comment.createdAt());
    }

    public record MemoThread(String screenId, String screenName, List<MemoComment> comments) { }

    public record MemoComment(String id, String authorName, String content, Instant createdAt) { }
}
