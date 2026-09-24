package com.bizplay.builder.srt;

import com.bizplay.builder.devrequest.DevRequestSourceFiles;
import org.springframework.stereotype.Component;

import java.util.List;

/** SRT 로 만든 요청서에 플로우 원문의 첨부 목록을 붙인다 — 파일은 내려받지 않는다 (목업 06b). */
@Component
class SrtSourceFiles implements DevRequestSourceFiles {

    private final SrtMapper srts;
    private final SrtService service;

    SrtSourceFiles(SrtMapper srts, SrtService service) {
        this.srts = srts;
        this.service = service;
    }

    @Override
    public List<SourceFile> of(String frdId) {
        Srt srt = frdId == null ? null : srts.selectByBridgeFrdId(frdId);
        if (srt == null) return List.of();
        return service.attachmentsOf(srt).stream()
                .map(file -> new SourceFile(file.name(), file.url(), file.size()))
                .toList();
    }
}
