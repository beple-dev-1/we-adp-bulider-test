package com.bizplay.builder.frd;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 화면별 대화와 한 FRD에서 하나만 실행되는 AI 수정 작업의 DB 상태를 관리한다. */
@Service
public class FrdScreenChatService {

    private static final Logger log = LoggerFactory.getLogger(FrdScreenChatService.class);
    private static final int MAX_MESSAGE_LENGTH = 4_000;

    private final FrdScreenChatMapper messages;

    public FrdScreenChatService(FrdScreenChatMapper messages) {
        this.messages = messages;
    }

    @Transactional(readOnly = true)
    public List<FrdScreenChatMessage> messages(String frdScreenId) {
        return messages.selectByScreenId(frdScreenId);
    }

    @Transactional(readOnly = true)
    public List<FrdScreenChatMessage> canvasMessages(String frdId) {
        return messages.selectCanvasByFrdId(frdId);
    }

    @Transactional(readOnly = true)
    public FrdScreenChatMessage running(String frdId) {
        return messages.selectRunningByFrdId(frdId);
    }

    /** 사용자 말과 그 답을 기다리는 AI 자리를 한 트랜잭션으로 만든다. */
    @Transactional
    public FrdScreenChatMessage start(String frdId, String frdScreenId, String request) {
        return start(frdId, frdScreenId, request, false);
    }

    @Transactional
    public FrdScreenChatMessage startCanvas(String frdId, String anchorScreenId, String request) {
        return start(frdId, anchorScreenId, request, true);
    }

    private FrdScreenChatMessage start(String frdId, String frdScreenId, String request, boolean canvas) {
        String content = request == null ? "" : request.strip();
        if (content.isBlank()) {
            throw new IllegalArgumentException("수정할 내용을 입력해 주세요.");
        }
        if (content.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("수정 요청은 4,000자 이내로 입력해 주세요.");
        }
        if (messages.selectRunningByFrdId(frdId) != null) {
            throw new IllegalStateException("다른 화면 요청을 AI가 처리하고 있습니다. 완료된 뒤 다시 요청해 주세요.");
        }
        int next = canvas ? messages.selectNextCanvasSequence(frdId) : messages.selectNextSequence(frdScreenId);
        Instant now = Instant.now();
        FrdScreenChatMessage user = new FrdScreenChatMessage(UUID.randomUUID().toString(), frdId, frdScreenId,
                next, FrdScreenChatMessage.Role.USER, FrdScreenChatMessage.State.DONE,
                content, null, null, now, now);
        FrdScreenChatMessage running = new FrdScreenChatMessage(UUID.randomUUID().toString(), frdId,
                frdScreenId, next + 1, FrdScreenChatMessage.Role.AI,
                FrdScreenChatMessage.State.RUNNING, null, null, null, now, null);
        if (canvas) {
            messages.insertCanvas(user);
            messages.insertCanvas(running);
        } else {
            messages.insert(user);
            messages.insert(running);
        }
        return running;
    }

    @Transactional
    public void complete(String id, String content, String sessionId) {
        messages.updateDone(id, content, sessionId, Instant.now());
    }

    @Transactional
    public void fail(String id, String failure) {
        messages.updateFailed(id, failure, Instant.now());
    }

    /** 서버 재기동으로 사라진 프로세스를 계속 실행 중이라고 보여 주지 않는다. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedChats() {
        int recovered = messages.failInterrupted(
                "서버가 다시 시작되어 화면 요청 처리가 중단되었습니다. 다시 요청해 주세요.", Instant.now());
        if (recovered > 0) {
            log.warn("중단된 FRD 화면 대화를 실패로 전환했다 실행={}개", recovered);
        }
    }
}
