package com.bizplay.builder.frd;

import com.bizplay.builder.solution.SolutionScreen;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 화면을 짚는 AI 가 적은 <b>시스템</b>을 기획 저장소의 값으로 바로잡는다.
 *
 * <p>⭐ <b>시스템은 레포가 가진 값이다</b> — {@code manifest.json} 의 {@code systems[].id} 그대로다
 * (V40 칸 설명). 기존 화면의 시스템은 {@code index.json} 에 이미 있으니 AI 에게 맡길 까닭이 없다.
 * 2026-09-23 FRD-006: AI 가 옛 규격 예시(다른 사업 g2c 의 {@code webview})를 적어 초안이
 * 「화면을 만들 pages 폴더가 없습니다: webview」로 떨어졌다. 시스템은 브랜치 이름
 * ({@code dr/<시스템>/…})에도 들어간다.
 *
 * <p>⛔ <b>시스템 이름을 여기에 적지 마라</b> — 어느 시스템이 있나는 사업 지식이고 정본은 클론이다.
 *
 * <p>⛔ DB 도 git 도 만지지 않는다 — 받은 값으로만 판정한다.
 */
final class ScreenPickSystems {

    private ScreenPickSystems() {
    }

    /**
     * @param index 기획 저장소 색인의 화면들. 비었으면(못 읽었으면) 바로잡을 근거가 없어 받은 그대로 돌려준다
     */
    static ScreenPickReader.Pick correct(ScreenPickReader.Pick pick, List<SolutionScreen> index) {
        if (index == null || index.isEmpty()) {
            return pick;
        }
        Map<String, String> systemOf = new HashMap<>();
        Set<String> systems = new HashSet<>();
        for (SolutionScreen screen : index) {
            if (screen.system() != null && !screen.system().isBlank()) {
                systemOf.put(screen.screenId(), screen.system());
                systems.add(screen.system());
            }
        }
        List<ScreenPickReader.Picked> fixed = pick.screens().stream()
                .map(picked -> withSystem(picked, systemOf, systems))
                .toList();
        return new ScreenPickReader.Pick(pick.title(), pick.items(), fixed, pick.noScreenReason());
    }

    private static ScreenPickReader.Picked withSystem(ScreenPickReader.Picked picked,
                                                      Map<String, String> systemOf, Set<String> systems) {
        String known = picked.newScreen() ? null : systemOf.get(picked.screenId());
        String system;
        if (known != null) {
            // ⭐ 기존 화면 — 색인이 정답이다. AI 가 무엇을 적었든.
            system = known;
        } else if (picked.system() != null && systems.contains(picked.system())) {
            // ⚠ 새 화면(또는 색인에 없는 화면) — 이 저장소에 있는 시스템이면 둔다.
            system = picked.system();
        } else {
            // ⛔ 없는 시스템은 비운다 — 지어낸 값으로 폴더를 찾으면 떨어진다.
            system = null;
        }
        if (java.util.Objects.equals(system, picked.system())) {
            return picked;
        }
        return new ScreenPickReader.Picked(picked.screenId(), system, picked.screenName(), picked.reason(),
                picked.newScreen(), picked.screenType());
    }
}
