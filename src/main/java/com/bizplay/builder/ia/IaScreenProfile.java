package com.bizplay.builder.ia;

/** 최초 색인에서 가져온 화면 분류. 이후 색인이 바뀌어도 DB 값을 덮어쓰지 않는다. */
public record IaScreenProfile(
        String structureId,
        String screenId,
        ScreenKind screenKind,
        ScreenType screenType,
        TypeSource typeSource) {

    public enum ScreenKind {
        SCREEN("화면"), POPUP("팝업"), MODAL("모달");

        private final String label;
        ScreenKind(String label) { this.label = label; }
        public String label() { return label; }

        public static ScreenKind fromLabel(String value) {
            return switch (value) {
                case "팝업" -> POPUP;
                case "모달" -> MODAL;
                default -> SCREEN;
            };
        }
    }

    public enum ScreenType {
        LIST("목록"), DETAIL("상세"), CREATE("등록"), EDIT("수정"),
        GUIDE("안내"), UNCLASSIFIED("미분류");

        private final String label;
        ScreenType(String label) { this.label = label; }
        public String label() { return label; }

        public static ScreenType fromLabel(String value) {
            return switch (value) {
                case "목록" -> LIST;
                case "상세" -> DETAIL;
                case "등록" -> CREATE;
                case "수정" -> EDIT;
                case "안내" -> GUIDE;
                default -> UNCLASSIFIED;
            };
        }
    }

    public enum TypeSource {
        ID("ID"), NAME("이름");

        private final String label;
        TypeSource(String label) { this.label = label; }
        public String label() { return label; }

        public static TypeSource fromLabel(String value) {
            if (value == null || value.isBlank()) return null;
            return switch (value) {
                case "ID" -> ID;
                case "이름" -> NAME;
                default -> null;
            };
        }
    }
}
