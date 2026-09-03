package com.bizplay.builder.frd;

import com.bizplay.builder.ai.ClaudeRunner.Progress;
import com.bizplay.builder.git.GitCommand;
import org.slf4j.Logger;

import java.util.List;

/** FRD의 모든 Claude 실행을 같은 모양으로 콘솔에 남긴다. */
final class FrdAiConsoleLog {

    private FrdAiConsoleLog() {
    }

    static void start(Logger log, String work, String context, String accountId,
                      List<String> arguments, String prompt) {
        log.info("FRD AI 실행 작업={} {} model={} effort={} accountId={}\n프롬프트:\n{}",
                work, context, modelOf(arguments), effortOf(arguments), accountId,
                GitCommand.mask(prompt));
    }

    static void progress(Logger log, String work, String context, int sequence, Progress step) {
        log.info("FRD AI 진행 작업={} {} 순번={} 종류={} 내용={}",
                work, context, sequence, step.kind(), GitCommand.mask(step.text()));
    }

    static String modelOf(List<String> arguments) {
        return optionOf(arguments, "--model", "기본값(미지정)");
    }

    static String effortOf(List<String> arguments) {
        return optionOf(arguments, "--effort", "기본값(미지정)");
    }

    private static String optionOf(List<String> arguments, String option, String fallback) {
        for (int index = 0; index + 1 < arguments.size(); index++) {
            if (option.equals(arguments.get(index))) return arguments.get(index + 1);
        }
        return fallback;
    }
}
