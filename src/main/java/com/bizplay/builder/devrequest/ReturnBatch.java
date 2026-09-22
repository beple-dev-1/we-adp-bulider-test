package com.bizplay.builder.devrequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 개발이 돌려보낸 배치가 <b>계약에 맞나</b>를 판정한다 — 받는 자리의 문 지킴이.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-dev-feedback-design.md}
 * 「⛔ ② 는 파일 하나씩 받지 않는다 — 배치로 받는다」.
 *
 * <p>⭐ <b>배치가 다 차고 전부 규격을 지나야 놓는다.</b> 하나라도 떨어지면 <b>통째로 거절하고
 * 아무것도 안 놓는다</b>. 파일 하나씩 즉시 반영하면 세 가지가 동시에 깨진다 —
 * 「누락」과 「안 바뀜」을 못 가르고, 기본 브랜치가 시점 혼합물이 되고, 무엇이 필수인지 정할
 * 자리가 없어진다.
 * ⛔ <b>「일단 놓고 나중에 맞추자」로 되돌리지 마라</b> — 반쪽 상태를 다음 FRD 가 as-is 로 읽는다.
 *
 * <p>⭐ <b>{@code unchanged} 는 배치가 찼는지 세는 값</b>이지 실제로 안 바뀌었다는 증명이 아니다.
 * ⛔ 이 둘을 뭉치지 마라 — 완결성 계산과 내용의 참거짓은 다른 물음이다.
 *
 * <p>⚠ <b>회신서의 이름과 꼴은 설계에 없다</b>(2026-09-23). 설계는 개발 API 로 받는 그림이라
 * 배치가 <b>요청 본문</b>이었다. git 통로에서는 파일이어야 해서 {@code DR-nnn/return.json} 을
 * 새로 정했다 — 바꾸려면 개발에 고지해야 한다.
 *
 * <p>⛔ <b>DB 도 git 도 만지지 않는다</b> — 값을 받아 판정만 한다. 그래서 시험이 가볍다.
 */
public final class ReturnBatch {

    /** ⛔ 이 파일 이름을 다른 곳에 다시 적지 마라 — 흩어지면 한쪽만 고쳐진다. */
    public static final String FILE = "return.json";

    private static final String CHANGED = "changed";
    private static final String UNCHANGED = "unchanged";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReturnBatch() {
    }

    /**
     * 판정 결과.
     *
     * @param filesToTake ⭐ {@code changed} 인 것만 담는다 — {@code unchanged} 는 파일이 아니다
     */
    public record Verdict(boolean accepted, List<String> rejections, List<String> filesToTake) {
    }

    /**
     * @param currentBase 지금 기획 저장소 기본 브랜치의 판. 회신서가 적은 기준과 다르면 통째로 거절한다
     */
    public static Verdict judge(ExpectedBack expected, String returnJson, String currentBase) {
        JsonNode returned;
        try {
            returned = JSON.readTree(returnJson);
        } catch (JsonProcessingException broken) {
            // ⛔ 못 읽은 것을 통과로 세면 반쪽이 들어온다. 거절이지 무시가 아니다.
            return new Verdict(false, List.of("회신서를 읽지 못했습니다 — " + FILE), List.of());
        }

        List<String> rejections = new ArrayList<>();
        String declaredBase = returned.path("base").asText(null);
        if (declaredBase == null || !declaredBase.equals(currentBase)) {
            /*
             * ⭐ 내용을 대조하지 않고 **기준 커밋 이후의 동시 변경**을 잡는 유일한 장치다.
             * ⛔ 「그냥 다시 보내면 된다」로 읽지 마라 — 같은 파일을 그대로 재전송하면 그 사이의
             *   남의 변경을 다시 덮는다. 거절된 대상만 새 기준으로 다시 만들어야 한다.
             */
            rejections.add("갈라 온 기준이 지금 기본 브랜치와 다릅니다 — 새 기준으로 다시 만들어 주십시오."
                    + " (회신 " + declaredBase + " · 지금 " + currentBase + ")");
        }

        Set<String> known = new LinkedHashSet<>();
        expected.screens().forEach(screen -> known.add(screen.screenId()));
        List<String> take = new ArrayList<>();

        for (JsonNode row : returned.path("screens")) {
            String screenId = row.path("screenId").asText(null);
            if (screenId == null || !known.contains(screenId)) {
                // ⛔ 목록 밖은 받지 않는다 — 대조 없이 자리를 지키는 유일한 문 지킴이다.
                rejections.add("돌려받을 목록에 없는 화면입니다: " + screenId);
                continue;
            }
            ExpectedBack.Screen screen = expected.screens().stream()
                    .filter(candidate -> candidate.screenId().equals(screenId)).findFirst().orElseThrow();
            for (String part : screen.required()) {
                String state = row.path(part).asText(null);
                if (state == null) {
                    rejections.add("필수 구성요소가 안 왔습니다: " + screenId + " 의 " + part);
                    continue;
                }
                if (!CHANGED.equals(state) && !UNCHANGED.equals(state)) {
                    rejections.add("모르는 상태값입니다: " + screenId + " 의 " + part + " = " + state);
                    continue;
                }
                if (CHANGED.equals(state)) {
                    take.add(pathOf(screen, part));
                }
            }
        }
        for (String screenId : known) {
            boolean came = false;
            for (JsonNode row : returned.path("screens")) {
                came |= screenId.equals(row.path("screenId").asText(null));
            }
            if (!came) {
                rejections.add("돌려받아야 할 화면이 안 왔습니다: " + screenId);
            }
        }

        boolean accepted = rejections.isEmpty();
        // ⭐ 하나라도 떨어지면 아무것도 안 놓는다.
        return new Verdict(accepted, List.copyOf(rejections), accepted ? List.copyOf(take) : List.of());
    }

    private static String pathOf(ExpectedBack.Screen screen, String part) {
        return switch (part) {
            case ExpectedBack.PAGES -> "core/" + screen.systemCode() + "/pages/" + screen.screenId() + ".html";
            case ExpectedBack.SCREEN_MD -> "core/" + screen.systemCode() + "/pages/" + screen.screenId() + ".md";
            /*
             * ⚠ 색인은 화면마다 한 조각이지만 파일은 저장소에 하나다 — 같은 경로가 여러 번 담길 수
             *   있고, 놓는 쪽에서 한 번만 꺼내면 된다.
             */
            case ExpectedBack.INDEX -> "index.json";
            default -> throw new IllegalStateException("모르는 구성요소입니다: " + part);
        };
    }
}
