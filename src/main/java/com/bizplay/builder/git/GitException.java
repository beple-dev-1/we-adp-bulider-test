package com.bizplay.builder.git;

public class GitException extends RuntimeException {
    public GitException(String message) {
        super(GitCommand.mask(message));
    }

    public GitException(String message, Throwable cause) {
        super(GitCommand.mask(message), cause);
    }
}
