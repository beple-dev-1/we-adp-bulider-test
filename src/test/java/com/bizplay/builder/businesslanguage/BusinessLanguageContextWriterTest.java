package com.bizplay.builder.businesslanguage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessLanguageContextWriterTest {

    @TempDir Path temporary;

    @Test
    void 두_문서가_있을_때만_AI_입력_파일을_쓴다() throws Exception {
        BusinessDocumentMapper mapper = mock(BusinessDocumentMapper.class);
        when(mapper.selectOne("0000001", BusinessDocumentKind.POLICY))
                .thenReturn(Optional.of(document(BusinessDocumentKind.POLICY, "## 정책")));
        when(mapper.selectOne("0000001", BusinessDocumentKind.STANDARD_TERMS))
                .thenReturn(Optional.of(document(BusinessDocumentKind.STANDARD_TERMS, "# 표준용어")));

        var files = new BusinessLanguageContextWriter(mapper).write("0000001", temporary).orElseThrow();

        assertThat(Files.readString(files.policy())).isEqualTo("## 정책");
        assertThat(Files.readString(files.standardTerms())).isEqualTo("# 표준용어");
        assertThat(files.instruction()).contains("정책서", "표준용어");
    }

    private static BusinessDocument document(BusinessDocumentKind kind, String content) {
        return new BusinessDocument("0000001", kind, content, "[]", Instant.now(), "0000001");
    }
}
