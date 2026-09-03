package com.bizplay.builder.businesslanguage;

final class BusinessDocumentSeedException extends RuntimeException {

    private final String reason;

    BusinessDocumentSeedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    BusinessDocumentSeedException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    String reason() {
        return reason;
    }
}
