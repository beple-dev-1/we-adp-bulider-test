package com.bizplay.builder.ai;

import java.io.IOException;

/**
 * 내용 분석이 실패했다 — <b>사람에게 할 말을 같이 지고 온다.</b>
 *
 * <p>⛔ <b>실패를 한 덩어리로 뭉치지 마라.</b> 갈래마다 <b>고칠 사람이 다르다</b> —
 * <ul>
 *   <li>인증서·네트워크 → <b>관리자</b>가 서버를 고쳐야 한다</li>
 *   <li>저쪽이 붐빔 → <b>아무도 안 고친다.</b> 잠시 뒤 다시 누르면 된다</li>
 *   <li>글자가 안 나옴 → <b>기획자</b>가 문서를 바꿔 올리면 된다</li>
 * </ul>
 * 뭉치면 기획자가 「다시 시도」를 영원히 누르거나, 고칠 수 있는 것을 못 고친다.
 *
 * <p>⚠ <b>말을 가르는 자리를 여기 하나로 둔다.</b> 예외 종류를 늘리고 부르는 쪽에
 * {@code catch} 사다리를 쌓으면, 갈래가 하나 늘 때마다 <b>일꾼이 같이 자란다.</b>
 * 무엇이 잘못됐는지 아는 것은 <b>저쪽에 말을 건 자리</b>이므로 말도 거기서 정한다.
 *
 * <p>⛔ <b>{@link #getMessage()} 를 화면에 내지 마라</b> — 그쪽은 개발자가 보는 원문이고
 * 키·URL 이 섞여 나올 수 있다. 화면은 {@link #userMessage()} 만 본다.
 */
public class DocumentUnderstandingException extends IOException {

    private final String userMessage;

    public DocumentUnderstandingException(String developerMessage, String userMessage, Throwable cause) {
        super(developerMessage, cause);
        this.userMessage = userMessage;
    }

    public DocumentUnderstandingException(String developerMessage, String userMessage) {
        this(developerMessage, userMessage, null);
    }

    /** 화면에 그대로 낼 말. ⛔ 개발자 원문과 갈라 둔다. */
    public String userMessage() {
        return userMessage;
    }
}
