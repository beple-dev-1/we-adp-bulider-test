package com.bizplay.builder.secret;

import com.bizplay.builder.config.BuilderProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SecretSealer {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * ⚠ `@Autowired` 를 지우지 마라. 생성자가 둘이면 스프링이 어느 것도 못 고르고
     * 무인자 생성자를 찾다가 `No default constructor found` 로 죽는다.
     * 아래 package-private 생성자는 테스트가 쓴다.
     */
    @Autowired
    public SecretSealer(BuilderProperties properties) {
        this(properties.secretKeyBase64());
    }

    SecretSealer(String secretKeyBase64) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(secretKeyBase64), "AES");
    }

    public Sealed seal(String plain) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new Sealed(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)), nonce);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("봉인할 수 없다", e);
        }
    }

    public String unseal(Sealed sealed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed.nonce()));
            return new String(cipher.doFinal(sealed.cipher()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("봉인을 풀 수 없다 — 열쇠가 다르거나 값이 깨졌다", e);
        }
    }
}
