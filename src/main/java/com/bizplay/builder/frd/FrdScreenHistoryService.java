package com.bizplay.builder.frd;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** FRD 화면 수정 이력을 현재 워크트리와 화면 상태로 되돌린다. */
@Service
public class FrdScreenHistoryService {

    private final FrdMapper frds;
    private final FrdScreenMapper screens;
    private final FrdScreenHistoryMapper histories;
    private final FrdScreenFiles screenFiles;

    public FrdScreenHistoryService(FrdMapper frds, FrdScreenMapper screens,
                                   FrdScreenHistoryMapper histories, FrdScreenFiles screenFiles) {
        this.frds = frds;
        this.screens = screens;
        this.histories = histories;
        this.screenFiles = screenFiles;
    }

    @Transactional
    public void restore(String projectId, String frdId, long historyId) {
        FrdScreenHistory history = histories.selectById(historyId);
        FrdScreen screen = history == null ? null : screens.selectById(history.frdScreenId());
        Frd frd = screen == null ? null : frds.selectById(screen.frdId());
        if (frd == null || !frd.id().equals(frdId) || !frd.projectId().equals(projectId)) {
            throw new IllegalArgumentException("그런 변경 이력이 없습니다.");
        }
        if (screen.state() == FrdScreen.State.GENERATING) {
            throw new IllegalStateException("AI 초안을 만드는 중에는 이전 버전으로 되돌릴 수 없습니다.");
        }
        String systemCode = screen.systemCode() == null || screen.systemCode().isBlank()
                ? frd.systemCode() : screen.systemCode();
        if (systemCode == null || systemCode.isBlank()) {
            throw new IllegalStateException("되돌릴 화면 파일 경로를 확인하지 못했습니다.");
        }
        Path target = screenFiles.existingHtml(projectId, frdId, systemCode,
                screen.screenId(), screen.facet());
        Path mdTarget = screenFiles.document(projectId, frdId, systemCode, screen.screenId());
        if (target == null) throw new IllegalStateException("되돌릴 화면 파일 경로를 확인하지 못했습니다.");
        try {
            Files.writeString(target, history.html(), StandardCharsets.UTF_8);
            if (history.md() != null) Files.writeString(mdTarget, history.md(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("이전 화면 파일을 복원하지 못했습니다.", failure);
        }
        screens.updateGenerated(screen.id(), history.html(), history.changes(), Instant.now());
    }
}
