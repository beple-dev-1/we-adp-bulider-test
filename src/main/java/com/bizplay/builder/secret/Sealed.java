package com.bizplay.builder.secret;

public record Sealed(byte[] cipher, byte[] nonce) {
}
