package com.bizplay.builder.usermanual;

/** 마지막 정상 사용자 매뉴얼과 함께 승격된 대표 화면 캡처의 불변 파일 포인터. */
public record UserManualCapture(String bundlePath, String fileName, String label,
                                Integer width, Integer height, String sha256) {
}
