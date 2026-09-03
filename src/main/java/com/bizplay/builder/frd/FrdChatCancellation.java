package com.bizplay.builder.frd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** FRD 채팅별 Claude 프로세스를 추적하고 사용자의 중단 요청을 실제 프로세스 종료로 연결한다. */
@Component
public class FrdChatCancellation {

    private static final Logger log = LoggerFactory.getLogger(FrdChatCancellation.class);

    private final ConcurrentHashMap<String, Process> running = new ConcurrentHashMap<>();
    private final Set<String> requested = ConcurrentHashMap.newKeySet();

    public void register(String messageId, Process process) {
        running.put(messageId, process);
        if (requested.contains(messageId)) killTree(process);
    }

    public void cancel(String messageId) {
        requested.add(messageId);
        Process process = running.get(messageId);
        if (process != null) {
            log.info("FRD 채팅 프로세스 중단 messageId={} pid={}", messageId, process.pid());
            killTree(process);
        }
    }

    public boolean isRequested(String messageId) {
        return requested.contains(messageId);
    }

    public void release(String messageId) {
        running.remove(messageId);
        requested.remove(messageId);
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
