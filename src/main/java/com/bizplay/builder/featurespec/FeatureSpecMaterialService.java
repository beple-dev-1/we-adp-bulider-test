package com.bizplay.builder.featurespec;

import com.bizplay.builder.solution.SolutionMockupService;
import com.bizplay.builder.solution.SolutionScreen;
import com.bizplay.builder.solution.SolutionScreenReader;
import com.bizplay.builder.solution.SolutionVariant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 기획 저장소의 화면 자료를 생성 시점의 고정 입력과 근거 목록으로 만든다. */
@Service
public class FeatureSpecMaterialService {

    private final SolutionMockupService solutions;
    private final SolutionScreenReader screens;
    private final ObjectMapper json;

    public FeatureSpecMaterialService(SolutionMockupService solutions, SolutionScreenReader screens,
                                      ObjectMapper json) {
        this.solutions = solutions;
        this.screens = screens;
        this.json = json;
    }

    public Snapshot snapshot(String projectId, String systemCode, String screenId) {
        List<SolutionScreen> all = solutions.screens(projectId);
        SolutionScreen screen = all.stream()
                .filter(item -> systemCode.equals(item.system()) && screenId.equals(item.screenId()))
                .findFirst().orElseThrow(() -> new MaterialException("MISSING_SCREEN"));
        String md = readRequired(projectId, systemCode + "/pages/" + screenId + ".md", "MISSING_MD");
        LinkedHashMap<String, String> htmlFiles = new LinkedHashMap<>();
        if (screen.hasVariants()) {
            int number = 1;
            for (SolutionVariant variant : screen.variants()) {
                htmlFiles.put("screen-" + number++ + ".html",
                        readRequired(projectId, screen.previewPath(variant.code()), "MISSING_HTML"));
            }
        } else {
            htmlFiles.put("screen.html", readRequired(projectId, screen.previewPath(null), "MISSING_HTML"));
        }
        String context = context(all, screen);
        Evidence evidence = evidence(md, htmlFiles, all, screen);
        return new Snapshot(screen, List.copyOf(all), md, Map.copyOf(htmlFiles), context,
                evidence.json(), evidence.ids(), fingerprint(md, htmlFiles, context, evidence.json()));
    }

    private String context(List<SolutionScreen> all, SolutionScreen screen) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("screenId", screen.screenId()); value.put("screenName", displayName(screen));
        value.put("systemCode", screen.system()); value.put("menuPath", text(screen.menuPath()));
        value.put("summary", text(screen.summary())); value.put("parentScreenId", text(screen.parentScreenId()));
        value.put("openingScreenIds", screen.openingScreenIds());
        value.put("screenIndex", all.stream().map(item -> Map.of(
                "systemCode", item.system(), "screenId", item.screenId(),
                "screenName", displayName(item), "menuPath", text(item.menuPath()))).toList());
        try {
            return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new MaterialException("INVALID_CONTEXT");
        }
    }

    private Evidence evidence(String md, Map<String, String> htmlFiles,
                              List<SolutionScreen> all, SolutionScreen target) {
        List<Map<String, String>> rows = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        String[] lines = md.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            addEvidence(rows, ids, "MD:line:" + (i + 1), lines[i].strip());
        }
        htmlFiles.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(file -> {
            int number = 1;
            for (Element element : Jsoup.parse(file.getValue()).select("[id],button,a,input,select,textarea")) {
                String anchor = element.id().isBlank() ? "node-" + number++ : element.id();
                String fact = element.tagName() + " " + text(element.attr("name")) + " "
                        + text(element.attr("type")) + " " + text(element.text());
                addEvidence(rows, ids, "HTML:" + file.getKey() + "#" + anchor, fact.strip());
            }
        });
        for (SolutionScreen item : all) {
            if (!item.system().equals(target.system())) continue;
            addEvidence(rows, ids, "IA:" + item.screenId(),
                    displayName(item) + " | " + text(item.menuPath()) + " | " + text(item.summary()));
        }
        try {
            return new Evidence(json.writeValueAsString(rows), Set.copyOf(ids));
        } catch (JsonProcessingException impossible) {
            throw new MaterialException("INVALID_EVIDENCE");
        }
    }

    private void addEvidence(List<Map<String, String>> rows, Set<String> ids, String id, String fact) {
        String unique = id;
        int suffix = 2;
        while (!ids.add(unique)) unique = id + "-" + suffix++;
        rows.add(Map.of("id", unique, "fact", fact.isBlank() ? "표시 내용 없음" : fact));
    }

    private String readRequired(String projectId, String relative, String reason) {
        try {
            Path core = screens.coreRoot(projectId).toAbsolutePath().normalize();
            Path file = screens.fileInClone(projectId, relative).toAbsolutePath().normalize();
            if (!file.startsWith(core) || !Files.isRegularFile(file)
                    || !file.toRealPath().startsWith(core.toRealPath())) throw new MaterialException(reason);
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (body.isBlank()) throw new MaterialException(reason);
            return body;
        } catch (IOException | RuntimeException unreadable) {
            if (unreadable instanceof MaterialException material) throw material;
            throw new MaterialException(reason);
        }
    }

    static String fingerprint(String md, Map<String, String> htmlFiles, String context, String evidenceJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, md);
            htmlFiles.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(item -> {
                update(digest, item.getKey()); update(digest, item.getValue());
            });
            update(digest, context); update(digest, evidenceJson);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("기능명세서 입력 지문을 계산할 수 없습니다.", unavailable);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }
    private static String displayName(SolutionScreen screen) {
        return screen.screenName() == null || screen.screenName().isBlank() ? screen.screenId() : screen.screenName();
    }
    private static String text(String value) { return value == null ? "" : value; }

    public record Snapshot(SolutionScreen screen, List<SolutionScreen> allScreens, String md,
                           Map<String, String> htmlFiles, String contextJson, String evidenceJson,
                           Set<String> evidenceIds, String fingerprint) {
        public Set<String> screenIds() {
            return allScreens.stream().filter(item -> item.system().equals(screen.system()))
                    .map(SolutionScreen::screenId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
    private record Evidence(String json, Set<String> ids) { }
    public static final class MaterialException extends RuntimeException {
        private final String reason;
        MaterialException(String reason) { super(reason); this.reason = reason; }
        public String reason() { return reason; }
    }
}
