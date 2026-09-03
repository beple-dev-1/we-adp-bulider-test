package com.bizplay.builder.featurespec;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 개정판 저장과 현재 포인터 전환을 하나의 트랜잭션으로 묶는다. */
@Service
public class FeatureSpecStorage {

    private final FeatureSpecMapper specs;

    public FeatureSpecStorage(FeatureSpecMapper specs) {
        this.specs = specs;
    }

    @Transactional
    public boolean save(String projectId, String systemCode, String screenId, String generationId,
                        String sourceFingerprint, String generatorVersion, String schemaVersion,
                        String contentJson, String evidenceJson, String documentHtml) {
        FeatureSpecCurrent current = specs.lockCurrent(projectId, systemCode, screenId).orElse(null);
        if (current == null || current.state() != FeatureSpecState.RUNNING
                || !generationId.equals(current.generationId())) {
            return false;
        }
        int revisionNo = current.currentRevisionNo() + 1;
        String revisionId = UUID.randomUUID().toString();
        specs.insertRevision(new FeatureSpecRevision(revisionId, projectId, systemCode, screenId,
                revisionNo, sourceFingerprint, generatorVersion, schemaVersion,
                contentJson, evidenceJson, documentHtml, null));
        return specs.promote(projectId, systemCode, screenId, generationId, revisionId, revisionNo) == 1;
    }
}
