package com.bizplay.builder.screendesign;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenDesignRendererTest {

    @Test
    void 본문은_캡처를_중복하지_않고_번호_설명표만_렌더한다() {
        ScreenDesignContent content = new ScreenDesignContent("카드 목록", "카드를 찾습니다.", "카드 > 목록",
                "backoffice", "CARD-001", "전체 적용",
                List.of(new ScreenDesignContent.Navigation("연결 화면", "CARD-002")),
                "## 검증\n- 카드명은 필수입니다. <script>alert(1)</script>",
                List.of(new ScreenDesignContent.Capture("default", "기본 화면", "screen-1.png", "", 1440, 900,
                        List.of(new ScreenDesignContent.Callout(1, "입력", "카드명", "카드명을 입력합니다.",
                                "필수값을 확인합니다.", "카드명 조건이 적용됩니다.")))));

        ScreenDesignRenderer renderer = new ScreenDesignRenderer();
        String overview = renderer.renderOverview(content);
        String html = renderer.renderBody(content);

        assertThat(overview).contains("1. 화면 개요", "카드를 찾습니다.", "전체 적용", "CARD-001");
        assertThat(html).contains("3. 화면 요소 명세", "3.1 기본 화면", "카드명", "카드명을 입력합니다.",
                        "필수값을 확인합니다.", "카드명 조건이 적용됩니다.", "4. 화면 이동", "CARD-002",
                        "5. 화면 변형", "scope=\"col\"")
                .doesNotContain("<img", "screen-1.png", "화면 명세\n", "<script>alert(1)</script>");
    }
}
