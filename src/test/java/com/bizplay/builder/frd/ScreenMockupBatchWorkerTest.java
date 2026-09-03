package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenMockupBatchWorkerTest {

    @Mock FrdScreenMapper screens;
    @Mock ScreenMockupWorker screenWorker;
    @Mock ScreenMockupService mockups;
    @InjectMocks ScreenMockupBatchWorker worker;

    @Test
    void 일괄_초안은_AI가_선택한_화면만_화면마다_따로_던진다() {
        FrdScreen aiSelected = FrdScreen.picked("ai-screen", "frd-1", "screen-a", "AI 선택 화면",
                "screen-a", null, "요구사항과 관련된 화면입니다.");
        FrdScreen userSelected = FrdScreen.picked("user-screen", "frd-1", "screen-b", "사용자 선택 화면",
                "screen-b", null, null);
        when(screens.selectByFrdId("frd-1")).thenReturn(List.of(aiSelected, userSelected));

        worker.generate("frd-1");

        verify(screenWorker).generate("ai-screen");
        verify(screenWorker, never()).generate("user-screen");
        verify(screenWorker, never()).generateNow(Mockito.anyString());
    }

    @Test
    void 던지기_전에_만드는_중으로_먼저_표시해_두_번_누름을_막는다() {
        FrdScreen aiSelected = FrdScreen.picked("ai-screen", "frd-1", "screen-a", "AI 선택 화면",
                "screen-a", null, "요구사항과 관련된 화면입니다.");
        when(screens.selectByFrdId("frd-1")).thenReturn(List.of(aiSelected));

        worker.generate("frd-1");

        InOrder order = Mockito.inOrder(mockups, screenWorker);
        order.verify(mockups).markGenerating("ai-screen");
        order.verify(screenWorker).generate("ai-screen");
    }
}
