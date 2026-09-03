package com.bizplay.builder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 멀티모달 문서 읽기의 설치 설정. <b>없으면 그 길만 닫히고 서버는 뜬다.</b>
 *
 * <p>✅ <b>사내 문서를 밖으로 보내도 되는지는 2026-08-16 에 병주가 「분석해도 된다」로 확정했다.</b>
 * 그러니 여기 값이 비어 있는 것은 <b>정책이 막아서가 아니라 키를 아직 안 앉혀서</b>다.
 *
 * <p>⛔ <b>그렇다고 {@link BuilderProperties} 에 합치지 마라.</b> 저쪽은 「없으면 서버가 안 뜬다」는
 * 값들이다. 멀티모달은 <b>받은 문서 중 스캔 PDF·그림에만</b> 쓰이므로, 키가 없다고 서버가 안 뜨면
 * 그 갈래를 한 건도 안 올리는 사업까지 발이 묶인다. 없으면 <b>그 길만 닫힌다.</b>
 *
 * <p>⛔ <b>API 키와 모델명을 코드에 박지 마라.</b> 공급자를 코드에 직접 결합하지 않는 것이
 * {@code DocumentUnderstandingClient} 를 둔 까닭이고, 그 약속이 여기서 깨지면 뜻이 없어진다.
 *
 * @param apiKey        공급자 API 키. <b>비어 있으면 멀티모달을 아예 안 쓴다</b> —
 *                      그 상태에서 스캔 PDF·그림을 올리면 「내용 분석 설정이 없다」로 앉는다
 * @param model         쓸 모델 이름. 공급자 문서의 이름을 그대로 적는다.
 *                      ⛔ <b>모델 이름에는 유통기한이 있다.</b> 2026-08-16 에 처음 넣은 기본값
 *                      {@code gemini-2.5-flash} 는 <b>그날 이미 죽은 이름이었다</b> —
 *                      2.0 은 2026-06-01 에 닫혔고 2.5 는 새로 붙는 키에 404 를 낸다.
 *                      <b>이름이 코드에 있는 것이 아니라 설정에 있는 것이 이 함정의 답이다</b> —
 *                      다음에 또 갈릴 때 코드를 안 고쳐도 된다.
 *                      ⚠ 어느 이름이 살아 있는지는 공급자의 deprecations 문서가 정본이다.
 *                      <br>⛔ <b>「가장 새 모델」을 기본값으로 고르지 마라 (2026-08-16 실측).</b>
 *                      막 나온 {@code gemini-3.7-flash} 는 <b>같은 문서로 두 번 다 503</b>
 *                      ({@code experiencing high demand})이었고 {@code gemini-3.6-flash} 는 두 번 다 200 이었다.
 *                      이 층이 하는 일은 <b>글자를 옮겨 적는 것</b>이라 최신 지능이 필요 없다 —
 *                      <b>붐비지 않는 쪽</b>이 낫다. 그래서 기본값이 3.6 이다
 * @param baseUrl       공급자 끝점의 뿌리. 사내 프록시를 태울 때 이것만 바꾼다
 * @param timeout       한 번 읽는 데 주는 상한
 * @param maxInlineBytes 본문에 실어 보낼 수 있는 원본 크기 상한.
 *                      ⚠ Base64 는 크기를 4/3 로 불린다 — 업로드 상한(20MB)을 그대로 쓰면
 *                      요청이 저쪽 상한을 넘는다. 넘는 파일은 보내기 전에 거절한다
 */
@ConfigurationProperties(prefix = "builder.document-understanding")
public record DocumentUnderstandingProperties(
        String apiKey,
        String model,
        String baseUrl,
        Duration timeout,
        Long maxInlineBytes) {

    /**
     * ⚠ 기본값을 여기서 채운다 — {@code application.yml} 에만 두면 <b>설정을 지운 순간 null 이 된다.</b>
     * ⛔ {@code apiKey} 에는 기본값이 없다: 그것이 「안 켜졌다」의 유일한 표시다.
     */
    public DocumentUnderstandingProperties {
        model = blankToNull(model) == null ? "gemini-3.6-flash" : model.strip();
        baseUrl = blankToNull(baseUrl) == null
                ? "https://generativelanguage.googleapis.com/v1beta"
                : baseUrl.strip().replaceAll("/+$", "");
        timeout = timeout == null ? Duration.ofMinutes(3) : timeout;
        maxInlineBytes = maxInlineBytes == null ? 15L * 1024 * 1024 : maxInlineBytes;
        apiKey = blankToNull(apiKey);
    }

    /** 설정이 앉아 있나. ⛔ 이 판정 하나로 갈린다 — 부르는 쪽에서 키를 다시 들여다보지 마라. */
    public boolean configured() {
        return apiKey != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
