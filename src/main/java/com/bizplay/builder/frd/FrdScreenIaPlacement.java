package com.bizplay.builder.frd;

import java.time.Instant;

/** FRD 신규 화면을 IA에서 어디에 둘지 기록한 의도다. */
public record FrdScreenIaPlacement(
        String frdScreenId,
        PlacementMode placementMode,
        String structureId,
        String menuPathKey,
        String anchorScreenId,
        ScreenKind screenKind,
        Status status,
        Source source,
        String developmentFileName,
        Instant updatedAt,
        String updatedBy) {

    public enum PlacementMode { MENU, CHILD, OPENER, UNRESOLVED }

    public enum ScreenKind {
        SCREEN("화면"), POPUP("팝업"), MODAL("모달");

        private final String label;

        ScreenKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static ScreenKind from(String value) {
            if (value == null || value.isBlank()) return SCREEN;
            return switch (value.strip().toUpperCase()) {
                case "POPUP", "팝업" -> POPUP;
                case "MODAL", "모달" -> MODAL;
                case "SCREEN", "화면" -> SCREEN;
                default -> throw new IllegalArgumentException("그런 화면 종류가 없습니다: " + value);
            };
        }
    }

    public enum Status { PROPOSED, CONFIRMED, INVALID }

    public enum Source { USER, AI, INHERITED }

    public boolean resolved() {
        return placementMode != PlacementMode.UNRESOLVED && status != Status.INVALID;
    }

    public String statusLabel() {
        if (status == Status.INVALID) return "오류";
        if (placementMode == PlacementMode.UNRESOLVED) return "미정";
        return status == Status.CONFIRMED ? "확정" : "제안";
    }
}
