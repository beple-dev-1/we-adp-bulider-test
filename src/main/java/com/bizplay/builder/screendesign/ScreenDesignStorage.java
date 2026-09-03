package com.bizplay.builder.screendesign;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 화면설계서 개정판 저장과 현재 포인터 전환을 한 트랜잭션으로 묶는다. */
@Service
public class ScreenDesignStorage {

    private final ScreenDesignMapper designs;

    public ScreenDesignStorage(ScreenDesignMapper designs) {
        this.designs = designs;
    }

    @Transactional
    public boolean save(String projectId, String systemCode, String screenId, String generationId,
                        String revisionId, String sourceFingerprint, String generatorVersion,
                        String schemaVersion, String contentJson, String documentHtml,
                        String bundlePath, String bundleManifestJson) {
        ScreenDesignCurrent current = designs.lockCurrent(projectId, systemCode, screenId).orElse(null);
        if (current == null || current.state() != ScreenDesignState.RUNNING
                || !generationId.equals(current.generationId())) {
            return false;
        }
        int revisionNo = current.currentRevisionNo() + 1;
        designs.insertRevision(new ScreenDesignRevision(revisionId, projectId, systemCode, screenId,
                revisionNo, sourceFingerprint, generatorVersion, schemaVersion, contentJson,
                documentHtml, bundlePath, bundleManifestJson, null));
        return designs.promote(projectId, systemCode, screenId, generationId, revisionId, revisionNo) == 1;
    }
}
