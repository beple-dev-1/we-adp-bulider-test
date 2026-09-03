package com.bizplay.builder.frd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Git 워크트리 준비와 짧은 DB 상태 전환의 순서를 조정한다. */
@Service
public class FrdDraftingService {

    private static final Logger log = LoggerFactory.getLogger(FrdDraftingService.class);

    private final FrdWorkspace workspaces;
    private final FrdService frds;
    private final FrdScreenMapper screens;

    public FrdDraftingService(FrdWorkspace workspaces, FrdService frds, FrdScreenMapper screens) {
        this.workspaces = workspaces;
        this.frds = frds;
        this.screens = screens;
    }

    /** 워크트리가 확인된 뒤에만 수정 중으로 바꾼다. */
    public void start(String projectId, String frdId) {
        FrdWorkspace.Prepared prepared;
        try {
            prepared = workspaces.ensure(projectId, frdId);
        } catch (IllegalStateException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "FRD 작업 공간을 준비하지 못했습니다. 저장소 상태를 확인한 뒤 다시 시도해 주세요.", failure);
        }
        if (!prepared.workspaceCreated()) {
            // ⭐ 기존 워크트리를 다시 쓸 때 기획 저장소 최신을 넣는다 — 새 워크트리는 클론 HEAD 에서 따니 필요 없다.
            //    ⚠ 못 맞춰도 시작은 간다 — 전송 전 검증이 「낡았다」를 알린다.
            try {
                workspaces.syncWithClone(projectId, frdId);
            } catch (RuntimeException failure) {
                log.warn("FRD 워크트리를 기획 저장소 최신에 맞추지 못했다 frdId={}", frdId, failure);
            }
        }
        try {
            frds.startDrafting(frdId);
        } catch (IllegalStateException rejected) {
            workspaces.rollback(prepared);
            throw rejected;
        } catch (RuntimeException failure) {
            workspaces.rollback(prepared);
            throw new IllegalStateException(
                    "FRD 작업 상태를 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.", failure);
        }
    }

    /** 워크트리를 새로 만든 뒤 화면별 작업 결과를 최초 상태로 되돌린다. */
    public void reset(String projectId, String frdId) {
        if (frds.of(projectId, frdId).state() != Frd.State.DRAFTING) {
            throw new IllegalStateException("수정 중인 FRD만 작업을 초기화할 수 있습니다.");
        }
        if (screens.selectByFrdId(frdId).stream()
                .anyMatch(screen -> screen.state() == FrdScreen.State.GENERATING)) {
            throw new IllegalStateException("AI 초안을 만드는 중에는 작업을 초기화할 수 없습니다.");
        }
        try {
            workspaces.reset(projectId, frdId);
            screens.resetByFrdId(frdId);
        } catch (IllegalStateException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "FRD 작업을 초기화하지 못했습니다. 저장소 상태를 확인한 뒤 다시 시도해 주세요.", failure);
        }
    }
}
