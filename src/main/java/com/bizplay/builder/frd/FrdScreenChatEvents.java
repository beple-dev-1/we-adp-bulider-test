package com.bizplay.builder.frd;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.util.DisconnectedClientHelper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** FRD 인터뷰와 화면 대화의 상태 변경을 브라우저에 실시간으로 알린다. */
@Component
public class FrdScreenChatEvents {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> clients = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "frd-chat-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public FrdScreenChatEvents() {
        heartbeat.scheduleAtFixedRate(this::heartbeat, 15, 15, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(String frdId) {
        SseEmitter emitter = new SseEmitter(0L);
        clients.computeIfAbsent(frdId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> remove(frdId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        send(frdId, emitter, "refresh");
        return emitter;
    }

    public void publish(String frdId) {
        for (SseEmitter emitter : clients.getOrDefault(frdId, new CopyOnWriteArrayList<>())) {
            send(frdId, emitter, "refresh");
        }
    }

    private void heartbeat() {
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : clients.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                send(entry.getKey(), emitter, "heartbeat");
            }
        }
    }

    private void send(String frdId, SseEmitter emitter, String eventName) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(sequence.incrementAndGet()))
                    .name(eventName)
                    .data(Instant.now().toString()));
        } catch (IOException | IllegalStateException disconnected) {
            remove(frdId, emitter);
        }
    }

    private void remove(String frdId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = clients.get(frdId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) clients.remove(frdId, emitters);
    }

    /** Spring 기본 판별에서 빠지는 Windows 한국어 연결 종료 메시지까지 확인한다. */
    static boolean isClientDisconnect(Throwable failure) {
        if (DisconnectedClientHelper.isClientDisconnectedException(failure)) return true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            String normalized = message.toLowerCase();
            if ((message.contains("호스트 시스템의 소프트웨어") && message.contains("중단되었습니다"))
                    || normalized.contains("aborted by the software in your host machine")
                    || normalized.contains("wsaeconnaborted")
                    || normalized.contains("10053")) {
                return true;
            }
        }
        return false;
    }

    @PreDestroy
    void close() {
        heartbeat.shutdownNow();
        clients.values().forEach(emitters -> emitters.forEach(SseEmitter::complete));
        clients.clear();
    }
}
