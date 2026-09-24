package com.bizplay.builder.devrequest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 올린 첨부의 이름은 꾸러미의 {@code attachments/} 폴더에 그대로 앉는다 — 경로를 떼고 못 쓰는 글자를 바꾼다. */
class DevRequestAttachmentNameTest {

    @Test
    void 올린_파일_이름에서_경로를_뗀다() {
        assertThat(DevelopmentRequestService.attachmentFileName("C:\\Users\\기획\\화면흐름.pdf")).isEqualTo("화면흐름.pdf");
        assertThat(DevelopmentRequestService.attachmentFileName("../../etc/passwd")).isEqualTo("passwd");
    }

    @Test
    void 폴더_이름으로_쓸_수_없는_글자는_바꾸고_빈_이름은_채운다() {
        assertThat(DevelopmentRequestService.attachmentFileName("일정:최종?.xlsx")).isEqualTo("일정_최종_.xlsx");
        assertThat(DevelopmentRequestService.attachmentFileName("..")).isEqualTo("attachment");
        assertThat(DevelopmentRequestService.attachmentFileName(null)).isEqualTo("attachment");
    }
}
