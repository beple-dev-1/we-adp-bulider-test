package com.bizplay.builder.screendesign;

import java.util.List;

/** 화면설계서 본문과 캡처 파일의 구조화된 불변 내용. */
public record ScreenDesignContent(
        String title,
        String purpose,
        String menuPath,
        String systemCode,
        String screenId,
        String applicationScope,
        List<Navigation> navigation,
        String sourceSpecification,
        List<Capture> captures) {

    public ScreenDesignContent {
        navigation = navigation == null ? List.of() : List.copyOf(navigation);
        captures = captures == null ? List.of() : List.copyOf(captures);
    }

    public ScreenDesignContent(String title, String purpose, String menuPath, List<Capture> captures) {
        this(title, purpose, menuPath, "", "", "", List.of(), "", captures);
    }

    public record Capture(String name, String label, String imageFile, String pdfFile,
                          int width, int height, List<Callout> callouts) {
        public Capture {
            callouts = callouts == null ? List.of() : List.copyOf(callouts);
        }
    }

    public record Callout(int number, String kind, String label, String description,
                          String validation, String result) {
        public Callout(int number, String kind, String label, String description) {
            this(number, kind, label, description, "", description);
        }
    }

    public record Navigation(String relation, String screenId) { }
}
