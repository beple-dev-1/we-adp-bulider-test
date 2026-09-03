package com.bizplay.builder.devrequest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentStatusTemplateContractTest {

    @Test
    void 목록은_전송_상태와_개발_상태를_갈라서_표시한다() throws Exception {
        String list = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-requests.html"),
                StandardCharsets.UTF_8);
        String detail = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-request.html"),
                StandardCharsets.UTF_8);

        assertThat(list)
                .contains("<th scope=\"col\">전송 상태</th><th scope=\"col\">개발 상태</th>")
                .contains("row.request().developmentStateClass()")
                .contains("row.request().developmentStateLabel()")
                .contains("row.request().canMergeDevelopment()");
        assertThat(detail)
                .contains("/{r}/merge")
                .contains(">개발 결과 반영</button>");
    }

    @Test
    void 상세는_전송완료_후_개발_상태를_표시한다() {
        DevelopmentRequest beforeSend = request(
                DevelopmentRequest.DeliveryState.NOT_SENT, null);
        DevelopmentRequest afterSend = request(
                DevelopmentRequest.DeliveryState.SENT, DevelopmentState.PROGRESS);

        DevelopmentRequestService.View beforeView = view(beforeSend);
        DevelopmentRequestService.View afterView = view(afterSend);

        assertThat(beforeView.stateLabel()).isEqualTo("대기");
        assertThat(beforeView.stateClass()).isEqualTo("status-badge--waiting");
        assertThat(afterView.stateLabel()).isEqualTo("개발 진행 중");
        assertThat(afterView.stateClass()).isEqualTo("status-badge--progress");
    }

    @Test
    void 상세_템플릿은_전송완료_건의_개발_상태를_직접_표시한다() throws Exception {
        String template = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-request.html"),
                StandardCharsets.UTF_8);

        assertThat(template)
                .contains("request.deliveryState().name() == 'SENT' ? request.developmentStateClass()")
                .contains("request.deliveryState().name() == 'SENT' ? request.developmentStateLabel()")
                .contains("th:if=\"${packageDownloadable}\"")
                .contains("download th:href=\"@{/projects/{p}/artifacts/dev-requests/{r}/download")
                .contains(">개발요청서 다운로드</a>");
    }

    private DevelopmentRequestService.View view(DevelopmentRequest request) {
        return new DevelopmentRequestService.View(
                request, null, null, Map.of(), Map.of(), Map.of(), false);
    }

    private DevelopmentRequest request(DevelopmentRequest.DeliveryState deliveryState,
                                       DevelopmentState developmentState) {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        return new DevelopmentRequest(
                "0000001", "0000001", 1, "0000001", 1,
                "전자결재 임시 저장", "backoffice", "익산", "{}", deliveryState,
                null, null, null, null, null, null, null, null,
                "a".repeat(40), "b".repeat(40), developmentState,
                null, null, null, null, now, now);
    }
}
