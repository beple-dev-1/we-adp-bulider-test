package com.bizplay.builder.secret;

import java.security.SecureRandom;

/**
 * 슈퍼관리자가 재발급하는 임시 비밀번호를 만든다.
 *
 * <p>⛔ 헷갈리는 글자를 뺐다 — {@code 0/O}, {@code 1/l/I}. 슈퍼관리자가 이것을 <b>손으로 옮겨 적어</b>
 * 사용자에게 전한다(전달은 Builder 밖에서 한다). 잘못 읽히면 로그인이 안 되고 원인이 안 보인다.
 */
public final class TemporaryPasswords {

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzACDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswords() {
    }

    /** 열두 자리. 최초 로그인 흐름의 여덟 자 하한을 넉넉히 넘긴다. */
    public static String next() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
