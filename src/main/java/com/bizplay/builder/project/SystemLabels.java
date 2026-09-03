package com.bizplay.builder.project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시스템 코드를 화면에 쓰는 말로 바꾸는 표 — 한 프로젝트 몫이다.
 *
 * <p>⛔ <b>정적 표로 만들지 마라.</b> 종전에 {@code SolutionScreen} 안의 상수 셋이 이 일을 했고,
 * 레포가 시스템을 여섯으로 늘리자 나머지 셋이 영문으로 떴다. 이름은 프로젝트마다 다르다.
 *
 * <p>⚠ <b>모르는 코드는 그대로 낸다.</b> 아직 이름을 안 넣은 시스템, 동기화가 아직 안 돈
 * 프로젝트가 실제로 있다 — 그때 빈칸을 내면 「시스템이 없는 화면」으로 보인다.
 */
public record SystemLabels(Map<String, String> byCode) {

    public static SystemLabels of(List<ProjectSystem> systems) {
        Map<String, String> labels = new LinkedHashMap<>();
        systems.forEach(system -> labels.put(system.systemCode(), system.label()));
        return new SystemLabels(Map.copyOf(labels));
    }

    public String label(String systemCode) {
        return byCode.getOrDefault(systemCode, systemCode);
    }
}
