package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 가 만든 to-be 화면을 서버가 검사해서 받는다. 순수 함수라 DB 없이 도는 시험이 제일 싸다
 * — {@link ScreenPickReaderTest} 와 같은 자리다.
 */
class ScreenMockupReaderTest {

    private final ScreenMockupReader reader = new ScreenMockupReader();

    @Test
    void html_과_바뀐_것_목록을_읽는다() throws IOException {
        ScreenMockupReader.Mockup mockup = reader.read("""
                {"html":"<html><head><title>작성</title></head><body><article>임시 저장</article></body></html>",
                 "changes":["상단 행동 영역에 임시저장 버튼 추가","하단에 자동 저장 안내 추가"]}""");

        assertThat(mockup.html()).contains("임시 저장");
        assertThat(mockup.changes()).hasSize(2);
    }

    @Test
    void html_이_비면_통째로_거절한다() {
        assertThatThrownBy(() -> reader.read("""
                {"html":"   ","changes":[]}"""))
                .isInstanceOf(IOException.class);
    }

    @Test
    void 바뀐_것이_없어도_통과한다() throws IOException {
        assertThat(reader.read("""
                {"html":"<html><head></head><body><article>그대로</article></body></html>"}""").changes()).isEmpty();
    }

    @Test
    void 바깥_문서_구조가_빠진_html_은_거절한다() {
        assertThatThrownBy(() -> reader.read("""
                {"html":"<article>본문만 반환</article>","changes":[]}"""))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("바깥 구조");
    }

    @Test
    void AI가_head를_바꿔도_원본_CSS_head를_보존한다() throws IOException {
        ScreenMockupReader.Mockup mockup = reader.read("""
                {"html":"<html><head><style>깨진 스타일</style></head><body>수정 본문</body></html>","changes":[]}""",
                "<html><head><link rel=\"stylesheet\" href=\"../assets/css/style.css\"></head><body>원본</body></html>");

        assertThat(mockup.html()).contains("href=\"../assets/css/style.css\"")
                .contains("수정 본문")
                .doesNotContain("깨진 스타일");
    }

    @Test
    void 워크트리에서_직접_수정한_html과_변경_설명을_읽는다() throws IOException {
        ScreenMockupReader.Mockup mockup = reader.readEdited(
                "{\"changes\":[\"안내 문구를 버튼 아래에 추가\"]}",
                "<html><head></head><body><p>수정된 화면</p></body></html>",
                "<html><head></head><body><p>원본 화면</p></body></html>");

        assertThat(mockup.html()).contains("수정된 화면");
        assertThat(mockup.changes()).containsExactly("안내 문구를 버튼 아래에 추가");
    }
}
