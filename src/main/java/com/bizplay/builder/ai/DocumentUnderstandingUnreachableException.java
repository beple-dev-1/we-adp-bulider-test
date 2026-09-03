package com.bizplay.builder.ai;

/**
 * <b>분석 서비스에 닿지도 못했다</b> — 인증서·프록시·네트워크처럼 <b>서버 쪽 설정</b> 탓이다.
 *
 * <p>⛔ <b>「문서를 못 읽었다」와 섞지 마라.</b> 둘은 고칠 사람이 다르다 —
 * 앞엣것은 <b>관리자</b>가 서버를 고쳐야 하고, 뒤엣것은 <b>기획자</b>가 문서를 바꿔 올리면 된다.
 * 섞으면 기획자가 「다시 시도」를 영원히 누르고 아무 일도 안 일어난다.
 *
 * <p>⚠ <b>2026-08-16 에 실제로 그랬다.</b> 사내망이 TLS 를 가로채 다시 서명하는데
 * (Somansa Root CA) 앱이 <b>그 CA 를 안 들고 있는 자바</b>로 떠 있었다. 화면에는
 * 「내용을 읽지 못했습니다 — 다시 시도하거나 글로 옮겨 등록해 주세요」가 떴다. <b>거짓말이었다.</b>
 *
 * <p><b>인증서로 막혔을 때 고치는 길 (2026-08-16 실측)</b>
 * <ul>
 *   <li><b>자바를 바꾼다</b> — 이 저장소의 정본은 {@code C:\Tools\jdks\openjdk17.0.12} 이고
 *       거기엔 사내 CA 가 이미 들어 있다. ⚠ 기계에 Adoptium 21 도 있는데 <b>그쪽엔 없다</b></li>
 *   <li><b>창 금고를 쓰게 한다</b> — {@code -Djavax.net.ssl.trustStoreType=Windows-ROOT}.
 *       브라우저가 믿는 것을 자바도 믿게 된다. 관리자 권한도 CA 설치도 필요 없다.
 *       ⛔ <b>윈도우 전용이다</b> — 운영(리눅스)에서는 사내 CA 를 그 기계의 금고에 넣어야 한다</li>
 * </ul>
 * ⛔ <b>인증서 검사를 끄는 코드를 넣지 마라.</b> 그러면 가로채는 것이 사내망인지 남인지 못 가린다.
 */
public class DocumentUnderstandingUnreachableException extends DocumentUnderstandingException {

    /** 화면에 뜨는 말. ⛔ 「다시 시도해 보라」로 바꾸지 마라 — 눌러도 안 된다. */
    private static final String USER_MESSAGE =
            "내용 분석 서비스에 연결하지 못했습니다 — 문서 문제가 아니라 서버 설정입니다. 관리자에게 문의해 주세요";

    public DocumentUnderstandingUnreachableException(String developerMessage, Throwable cause) {
        super(developerMessage, USER_MESSAGE, cause);
    }
}
