package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrdChatCancellationTest {

    @Test
    void 프로세스가_등록되기_전에_중단해도_등록되는_즉시_종료한다() {
        FrdChatCancellation cancellations = new FrdChatCancellation();
        Process process = mock(Process.class);
        when(process.descendants()).thenReturn(Stream.empty());

        cancellations.cancel("message-1");
        cancellations.register("message-1", process);

        assertThat(cancellations.isRequested("message-1")).isTrue();
        verify(process).destroyForcibly();
        cancellations.release("message-1");
        assertThat(cancellations.isRequested("message-1")).isFalse();
    }
}
