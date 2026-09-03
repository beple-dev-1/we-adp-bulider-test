package com.bizplay.builder.git;

public record GitResult(int exitCode, String stdout, String stderr) {
    public boolean succeeded() {
        return exitCode == 0;
    }
}
