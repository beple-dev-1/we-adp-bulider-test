package com.bizplay.builder.usermanual;

import java.util.List;

/** AI가 돌려준 사용자 매뉴얼의 구조화된 내용이다. */
public record UserManualDocument(
        String title,
        String overview,
        String overviewEvidence,
        List<OpeningStep> openingSteps,
        List<Task> tasks,
        List<Field> fields,
        List<NextScreen> nextScreens
) {

    public UserManualDocument {
        openingSteps = immutable(openingSteps);
        tasks = immutable(tasks);
        fields = immutable(fields);
        nextScreens = immutable(nextScreens);
    }

    /** 현재 화면을 여는 한 단계와 그 근거다. */
    public record OpeningStep(String text, String evidence) {
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /** 화면에서 할 수 있는 일 한 가지다. */
    public record Task(String title, List<String> steps, String result, String evidence) {
        public Task {
            steps = immutable(steps);
        }
    }

    /** 화면 항목 하나의 설명이다. */
    public record Field(String name, String description, String evidence) {
    }

    /** 현재 화면에서 이어지는 화면 하나다. */
    public record NextScreen(String name, String description, String evidence) {
    }
}
