package com.bizplay.builder.frd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 「변경 예정 기능정의서」 AI 출력을 읽는다.
 *
 * <p>⛔ <b>파일 쓰기를 AI 에 주지 않는다.</b> md 를 <b>응답 JSON 안에서</b> 받는다 —
 * 목업과 사정이 다르다. 목업은 html 뼈대를 보존해야 해서 파일을 직접 고치게 했지만,
 * 기능정의서는 <b>글 한 장을 새로 쓰는 것</b>이라 파일 권한을 열 이유가 없다.
 */
@Component
public class ScreenTobeDocumentReader {

    /** ⚠ 이보다 길면 안 받는다 — 화면 md 는 규격상 한 장이다. 길면 딴 것을 쓴 것이다. */
    static final int MAX_LENGTH = 40_000;

    private final ObjectMapper json;

    public ScreenTobeDocumentReader(ObjectMapper json) {
        this.json = json;
    }

    public String read(String output) throws IOException {
        JsonNode root = json.readTree(stripFence(output));
        JsonNode md = root.path("md");
        if (md.isMissingNode() || md.isNull() || md.asText().isBlank()) {
            throw new IOException("기능정의서(md)가 비어 있습니다.");
        }
        String body = md.asText().strip();
        if (body.length() > MAX_LENGTH) {
            throw new IOException("기능정의서가 " + MAX_LENGTH + "자를 넘습니다.");
        }
        // ⛔ 「못 만들었다」를 글로 받지 않는다 — 그걸 저장하면 계약서가 사과문을 싣는다.
        if (!body.contains("---")) {
            throw new IOException("화면 md 규격의 블록 구분(---)이 없습니다.");
        }
        return body;
    }

    /** ⚠ 모델이 코드 울타리로 감싸는 일이 잦다. */
    private String stripFence(String output) {
        String text = output == null ? "" : output.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstBreak = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstBreak < 0 || lastFence <= firstBreak) {
            return text;
        }
        return text.substring(firstBreak + 1, lastFence).strip();
    }
}
