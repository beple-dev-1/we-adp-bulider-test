package com.bizplay.builder.ai;

import com.bizplay.builder.config.DocumentUnderstandingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * {@link DocumentUnderstandingClient} 의 Gemini 구현.
 *
 * <p>⛔ <b>부르는 쪽이 이 클래스를 알면 안 된다.</b> 공급자를 갈아 끼우는 자리가 여기 하나여야
 * {@code DocumentUnderstandingClient} 를 둔 뜻이 산다.
 *
 * <p>⛔ <b>API 키·모델명·끝점을 코드에 박지 않는다</b> — 전부
 * {@link DocumentUnderstandingProperties} 에서 온다. 설정이 없으면 {@link #available()} 이 거짓이고
 * <b>이 클래스는 아무것도 안 한다.</b>
 *
 * <p>⚠ <b>아직 실물 API 에 대고 안 쟀다.</b> 사내 문서 외부 전송은 2026-08-16 에
 * 「분석해도 된다」로 정해졌지만 <b>키를 아직 안 받았다</b> — 그래서 기본 설정에 키가 없고,
 * 없으면 스캔 PDF·그림이 「내용 분석 설정이 없다」로 앉는다.
 * <b>「돌아갈 것이다」를 「돈다」로 읽지 마라</b> — 요청 몸의 모양과 응답 파싱은 아직 문서만 보고 짠 것이다.
 *
 * <p>⚠ HTTP 는 JDK 내장 {@link HttpClient} 로 한다 — 이 저장소에 HTTP 클라이언트 의존이
 * 아직 하나도 없고, 부르는 곳이 여기 하나라 라이브러리를 들일 값이 없다.
 */
@Component
public class GeminiDocumentUnderstanding implements DocumentUnderstandingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiDocumentUnderstanding.class);

    /** 붐빌 때 몇 번까지 거나. ⛔ 늘리지 마라 — 파일을 통째로 다시 올리는 요청이라 값이 든다. */
    private static final int BUSY_ATTEMPTS = 3;

    /** 다시 걸기 전에 쉬는 시간. 회차마다 곱해진다(3초 → 6초). */
    private static final java.time.Duration BUSY_BACKOFF = java.time.Duration.ofSeconds(3);

    /**
     * 저쪽에 시키는 말.
     *
     * <p>⛔ <b>요약을 시키지 않는다.</b> 이 층의 산출물은 「문서의 글자」이지 「요지」가 아니다 —
     * 없는 말이 섞이면 뒤의 요구사항이 통째로 그 위에 선다.
     *
     * <p>⛔ <b>못 읽은 자리를 지어내지 말고 표시하라고 못 박는다.</b> 그러지 않으면
     * 흐린 스캔의 빈칸이 그럴듯한 문장으로 메워지고 <b>아무도 못 알아챈다.</b>
     */
    private static final String INSTRUCTION = """
            첨부한 문서를 읽고 **문서에 적힌 내용을 글로 그대로 옮겨라.**

            - 요약하지 마라. 문서에 있는 문장을 빠뜨리지 말고 옮긴다.
            - 없는 내용을 채우거나 추측해서 보태지 마라.
            - 표는 마크다운 표로, 제목은 마크다운 제목으로 구조를 되살린다.
            - 그림·도식 안에 업무 정보(항목명·흐름·조건)가 있으면 글로 옮긴다.
            - 흐리거나 가려서 읽을 수 없는 자리는 그 자리에 `[확인 필요]` 라고 적는다.
            - 사람 이름과 날짜, 숫자는 문서 그대로 둔다.

            **옮긴 본문만 출력해라.** 설명·머리말·「알겠습니다」 같은 말을 앞뒤에 붙이지 마라.
            """;

    private final DocumentUnderstandingProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public GeminiDocumentUnderstanding(DocumentUnderstandingProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    @Override
    public boolean available() {
        return properties.configured();
    }

    @Override
    public String read(Path file, String mediaType) throws IOException {
        if (!available()) {
            // ⛔ 「저쪽이 터졌다」와 섞지 마라 — 고칠 자리가 설정이라는 것을 사람이 알아야 한다.
            throw new IOException("내용 분석 설정(builder.document-understanding.api-key)이 없다");
        }
        long size = Files.size(file);
        if (size > properties.maxInlineBytes()) {
            throw new IOException("내용 분석에 보내기에 파일이 너무 크다 — %d바이트까지만 보낸다"
                    .formatted(properties.maxInlineBytes()));
        }

        String body = requestBody(Files.readAllBytes(file), mediaType);
        HttpResponse<String> response = sendWithRetry(body);
        String text = firstText(response.body());
        if (text == null || text.isBlank()) {
            // ⛔ 빈 글자를 성공으로 넘기지 않는다. 이 저장소가 두 번 데인 자리다.
            throw new IOException("내용 분석에서 글자가 나오지 않았다");
        }
        return text;
    }

    /**
     * 붐비면 <b>스스로 몇 번 다시 건다.</b>
     *
     * <p>⚠ <b>2026-08-16 실측</b> — 같은 문서·같은 모델이 한 번은 503
     * ({@code "This model is currently experiencing high demand"}), 곧이어 200 이었다.
     * <b>일시적인 혼잡을 사람에게 「문서 오류」로 내밀면 안 된다.</b>
     *
     * <p>⛔ <b>끝없이 다시 걸지 마라.</b> 파일을 통째로 다시 올리는 요청이라 값이 든다 —
     * 세 번으로 끊고, 그래도 붐비면 사람에게 「잠시 뒤」라고 말한다.
     * ⛔ <b>4xx 는 다시 걸지 마라</b>(429 만 예외다) — 요청이 틀린 것이라 백 번 걸어도 같다.
     */
    private HttpResponse<String> sendWithRetry(String body) throws IOException {
        HttpResponse<String> response = null;
        for (int attempt = 1; attempt <= BUSY_ATTEMPTS; attempt++) {
            response = send(body);
            if (response.statusCode() / 100 == 2) {
                return response;
            }
            if (!isBusy(response.statusCode()) || attempt == BUSY_ATTEMPTS) {
                break;
            }
            log.info("내용 분석이 붐빈다 — 다시 건다 ({}/{}) status={}",
                    attempt, BUSY_ATTEMPTS, response.statusCode());
            sleep(BUSY_BACKOFF.multipliedBy(attempt));
        }

        // ⛔ 저쪽 본문을 그대로 로그에 붓지 않는다 — 키가 되비쳐 나올 수 있다.
        log.warn("내용 분석이 거절됐다 status={}", response.statusCode());
        if (isBusy(response.statusCode())) {
            throw new DocumentUnderstandingException(
                    "내용 분석이 붐벼서 거절됐다 (status=" + response.statusCode() + ")",
                    "내용 분석 서비스가 잠시 붐빕니다 — 잠시 뒤 다시 시도해 주세요");
        }
        throw new DocumentUnderstandingException(
                "내용 분석 요청이 거절됐다 (status=" + response.statusCode() + ")",
                "내용을 읽지 못했습니다 — 다시 시도하거나 글로 옮겨 등록해 주세요");
    }

    /** ⚠ 429(한도)와 5xx(저쪽 사정)만 다시 건다. 그 밖의 4xx 는 우리가 틀린 것이다. */
    private static boolean isBusy(int status) {
        return status == 429 || status / 100 == 5;
    }

    private static void sleep(java.time.Duration nap) throws IOException {
        try {
            Thread.sleep(nap.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("내용 분석이 중단됐다");
        }
    }

    /**
     * ⚠ <b>키를 주소에 실어 보낸다</b>(공급자 규약). 그래서 이 URI 를 로그에 찍지 마라 —
     * 예외 메시지에도 안 들어가게 아래에서 상태 코드만 꺼낸다.
     */
    private HttpResponse<String> send(String body) throws IOException {
        URI uri = URI.create("%s/models/%s:generateContent?key=%s"
                .formatted(properties.baseUrl(), properties.model(), properties.apiKey()));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (SSLException certificate) {
            // ⛔ 「문서를 못 읽었다」로 뭉치지 마라 — 고칠 사람이 관리자다.
            //   ⚠ 사내망이 TLS 를 가로채 다시 서명하면 그 CA 를 안 들고 있는 자바에서 여기로 온다
            //     (2026-08-16 실측: PKIX path building failed).
            throw new DocumentUnderstandingUnreachableException(
                    "분석 서비스의 인증서를 확인하지 못했다 — 서버가 사내 CA 를 믿게 해야 한다", certificate);
        } catch (ConnectException | HttpTimeoutException unreachable) {
            throw new DocumentUnderstandingUnreachableException(
                    "분석 서비스에 연결하지 못했다 — 네트워크나 프록시 설정을 봐야 한다", unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("내용 분석이 중단됐다");
        }
    }

    /**
     * ⚠ <b>Jackson 으로 만든다.</b> 문자열을 이어 붙이면 지시문의 따옴표·줄바꿈이 JSON 을 깨뜨리고,
     * 그것을 저쪽이 「잘못된 요청」으로 돌려주면 <b>원인이 파일에 있는 줄 알게 된다.</b>
     */
    private String requestBody(byte[] fileBytes, String mediaType) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode parts = root.putArray("contents").addObject().putArray("parts");
        parts.addObject().put("text", INSTRUCTION);
        ObjectNode inline = parts.addObject().putObject("inline_data");
        inline.put("mime_type", mediaType);
        inline.put("data", Base64.getEncoder().encodeToString(fileBytes));
        // ⛔ 0 으로 둔다 — 같은 문서를 두 번 읽으면 같은 글이 나와야 사람이 대조할 수 있다.
        root.putObject("generationConfig").put("temperature", 0);
        return mapper.writeValueAsString(root);
    }

    /**
     * ⛔ <b>모르면 실패다.</b> 모양이 조금이라도 다르면 {@code null} 을 돌려주고 부르는 쪽이 던진다 —
     * 지어낸 기본값을 문서 내용 자리에 앉히지 않는다.
     */
    private String firstText(String responseBody) {
        try {
            JsonNode json = mapper.readTree(responseBody);
            JsonNode parts = json.path("candidates").path(0).path("content").path("parts");
            if (!parts.isArray()) {
                return null;
            }
            StringBuilder joined = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.path("text").isTextual()) {
                    joined.append(part.get("text").asText());
                }
            }
            return joined.toString();
        } catch (IOException unparsable) {
            log.warn("내용 분석 응답을 JSON 으로 못 읽었다");
            return null;
        }
    }
}
