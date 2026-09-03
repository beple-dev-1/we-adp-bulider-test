package com.bizplay.builder.checker;

import java.util.List;

/**
 * 검사기를 한 번 돌린 결과 전체.
 *
 * <p>⚠ <b>「진단이 0건」과 「판정을 못 냈다」는 다르다.</b> 둘을 뭉치면 검사기가 아예 못 도는 상태가
 * <b>초록으로 보이고 저장이 통째로 열린다.</b> 실물에서 그 자리는 {@code npm install} 이 안 돼 있을 때다 —
 * 검사기가 <b>stdout 0바이트 + 종료코드 1</b> 로 끝난다(2026-08-14 실측).
 */
public record CheckReport(Verdict verdict, List<Finding> findings) {

    public CheckReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public enum Verdict {
        /** 검사기가 돌아서 판정을 냈다. 진단이 0건일 수도 있다. */
        CHECKED,
        /** 검사기를 못 돌렸다. ⛔ <b>초록으로 읽지 마라.</b> */
        UNKNOWN
    }

    /** 판정을 못 냈을 때. 진단은 없다. */
    public static CheckReport unknown() {
        return new CheckReport(Verdict.UNKNOWN, List.of());
    }

    public boolean isUnknown() {
        return verdict == Verdict.UNKNOWN;
    }

    public long redCount() {
        return findings.stream().filter(it -> it.level() == Finding.Level.RED).count();
    }

    public long reviewCount() {
        return findings.stream().filter(it -> it.level() == Finding.Level.REVIEW).count();
    }
}
