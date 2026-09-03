package com.bizplay.builder.screendesign;

import com.bizplay.builder.screendesign.ScreenDesignMaterialService.Snapshot;
import com.bizplay.builder.solution.ScreenHistory;
import com.bizplay.builder.solution.SolutionScreen;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenDesignContentAssemblerTest {

    @Test
    void 화면_MD의_검증과_결과를_조작_요소에_구조화한다() {
        SolutionScreen screen = new SolutionScreen("CARD-001", "카드 조회", "카드를 조회합니다.",
                "backoffice", "목록", "기본", "근거", "카드 > 조회", "", null, null,
                List.of(), List.of("본점"), "CARD-000", List.of("CARD-002"), false, ScreenHistory.EMPTY);
        String markdown = """
                --- 정의 ---
                - 구분: 기능 / 좌표: id=btnSearch / 라벨: 카드 조회 / 동작: 조회 조건을 전송합니다. / 검증: 카드번호 형식을 확인합니다. / 결과: 조건에 맞는 카드 목록을 표시합니다.
                """;
        Snapshot snapshot = new Snapshot(screen, List.of(), markdown, "{}", Path.of("core"), "fingerprint");
        ScreenDesignContent.Capture capture = new ScreenDesignContent.Capture("default", "기본 화면",
                "screen-1.png", "", 1600, 900, List.of(
                new ScreenDesignContent.Callout(1, "버튼", "카드 조회", "카드 조회 기능을 실행합니다.")));

        ScreenDesignContent content = ScreenDesignContentAssembler.assemble(snapshot, List.of(capture));

        ScreenDesignContent.Callout callout = content.captures().get(0).callouts().get(0);
        assertThat(callout.description()).isEqualTo("조회 조건을 전송합니다.");
        assertThat(callout.validation()).isEqualTo("카드번호 형식을 확인합니다.");
        assertThat(callout.result()).isEqualTo("조건에 맞는 카드 목록을 표시합니다.");
        assertThat(content.navigation()).extracting(ScreenDesignContent.Navigation::screenId)
                .containsExactly("CARD-000", "CARD-002");
    }
}
