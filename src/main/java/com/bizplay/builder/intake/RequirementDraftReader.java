package com.bizplay.builder.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 가 낸 요구사항 초안 JSON 을 <b>서버가 검사해서</b> 받는다.
 *
 * <p>⛔ <b>검사 없이 저장하지 마라.</b> 저쪽이 낸 것은 사람이 부탁한 모양일 뿐 보장이 아니다 —
 * 제목이 빈 요구사항이 앉으면 목록에서 누를 수도 지울 수도 없는 줄이 남는다.
 *
 * <p>⛔ <b>모르면 실패다.</b> 모양이 다르면 반쯤 건져서 저장하지 않고 통째로 던진다 —
 * 반쯤 앉은 요구사항은 번호를 태우고, <b>번호는 되돌릴 수 없다.</b>
 *
 * <p>⚠ <b>앞뒤에 붙은 말을 걷어내 준다.</b> 지시문이 「JSON 만 출력해라」라고 못 박아도
 * 모델은 종종 {@code ```json} 울타리를 두른다 — 그것 하나 때문에 실패로 보내면
 * 사람이 다시 누르는 것 말고 할 수 있는 것이 없다.
 */
@Component
public class RequirementDraftReader {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 초안 한 건. ⚠ {@code screenHints} 는 없어도 된다 — 참고 정보다. */
    public record Draft(String title, String body, String screenHints) {
    }

    /** 받은 문서 한 건을 대표하는 요구사항 한 건을 읽는다. */
    public Draft read(String output) throws IOException {
        JsonNode root = mapper.readTree(stripFence(output));
        JsonNode item = root.path("requirement");
        if (!item.isObject()) {
            throw new IOException("단일 요구사항(requirement)이 없는 결과다");
        }
        String title = text(item, "title");
        String body = text(item, "body");
        if (title == null || body == null) {
            throw new IOException("제목이나 본문이 빈 요구사항이다");
        }
        return new Draft(cut(title, 255), body, text(item, "screens"));
    }

    /**
     * ⚠ 울타리({@code ```json ... ```})와 앞뒤 잡말을 걷어낸다.
     * <b>첫 {@code &#123;} 부터 마지막 {@code &#125;} 까지</b>만 남긴다 — 중괄호가 없으면 그대로 두고
     * 파싱이 던지게 한다(여기서 지어내지 않는다).
     */
    private String stripFence(String output) {
        if (output == null) {
            return "";
        }
        int opens = output.indexOf('{');
        int closes = output.lastIndexOf('}');
        return opens >= 0 && closes > opens ? output.substring(opens, closes + 1) : output;
    }

    private String text(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (value.isArray()) {
            // 화면 후보처럼 배열로 오는 자리가 있다 — 사람이 읽는 한 줄로 이어 붙인다.
            List<String> parts = new ArrayList<>();
            value.forEach(each -> {
                if (each.isTextual() && !each.asText().isBlank()) {
                    parts.add(each.asText().strip());
                }
            });
            return parts.isEmpty() ? null : String.join(", ", parts);
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().strip();
    }

    /** ⚠ 제목 열이 {@code varchar(255)} 다 — 넘치면 DB 가 통째로 거절한다. */
    private String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
