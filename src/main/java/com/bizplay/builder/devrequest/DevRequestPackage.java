package com.bizplay.builder.devrequest;

import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 전송 꾸러미의 <b>화면 층과 {@code manifest.json}</b> 을 만든다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}.
 *
 * <p>⭐ <b>재료는 완료 커밋 둘이다.</b> as-is 는 앞판({@code workspace_base_sha}),
 * to-be 는 뒷판({@code workspace_head_sha}). 워크트리는 전송이 끝나기 전에 지워지지 않으므로
 * 같은 자리에서 둘 다 뽑힌다.
 * ⛔ <b>전송이 끝나기 전에 워크트리를 지우는 코드를 넣지 마라</b> — 계약서를 만들 재료가 사라진다.
 *
 * <p>⭐ <b>as-is 를 함께 담는 것이 계약의 뼈대다.</b> to-be 만 주면 개발이 diff 를 스스로 짜고,
 * 뒤에 「원래 그랬다/아니다」를 가릴 근거가 없다.
 *
 * <p>⛔ <b>여기서 DB 를 읽지 마라.</b> 재료를 값으로 받는 순수한 자리로 둔다 — 그래서 임시 저장소
 * 하나로 시험할 수 있다. DB 를 뒤져 {@link Request} 를 채우는 일은 부르는 쪽이 한다.
 *
 * <p>⛔ <b>{@code README} 를 넣지 마라</b> (2026-08-25 교정). 어떤 파일이 무엇인가는
 * {@code dev-request.md} 8절이 {@code manifest.json} 에서 만들어 적고, 규격 판은 아래
 * {@code specVersion} 칸이다. README 를 넣으면 갈리는 셋째 사본이 된다.
 */
public class DevRequestPackage {

    /** ⚠ {@code expected-back.md} 가 필수가 된 판의 번호다. 배치를 바꾸면 이 값을 올린다. */
    private static final int SPEC_VERSION = 2;

    private final GitCommand git;
    private final Duration timeout;

    public DevRequestPackage(GitCommand git, Duration timeout) {
        this.git = git;
        this.timeout = timeout;
    }

    /** 꾸러미에 담을 화면 하나. {@code changes} 는 그 화면의 변경 목록이다. */
    public record Screen(String systemCode, String screenId, String displayName, List<String> changes) {
    }

    /**
     * 꾸러미 하나의 재료.
     *
     * @param label      {@code DR-009} 꼴
     * @param asIsCommit 완료 커밋의 앞판
     * @param toBeCommit 완료 커밋의 뒷판
     */
    public record Request(String label, String asIsCommit, String toBeCommit, List<Screen> screens) {
    }

    /**
     * {@code outDir} 에 꾸러미의 화면 층과 {@code manifest.json} 을 쓴다.
     *
     * <p>⚠ <b>화면 0장이 정상이다</b> — 백엔드만인 FRD 는 워크트리 커밋도 없다. 그때
     * {@code screens/} 를 만들지 않고 {@code manifest.screens} 를 빈 목록으로 둔다.
     */
    public Path write(Path worktree, Request request, Path outDir) {
        try {
            Files.createDirectories(outDir);
            List<Map<String, Object>> screens = new ArrayList<>();
            for (Screen screen : request.screens()) {
                screens.add(writeScreen(worktree, request, screen, outDir));
            }
            Files.writeString(outDir.resolve("manifest.json"),
                    manifest(request, screens), StandardCharsets.UTF_8);
            return outDir;
        } catch (IOException failed) {
            throw new UncheckedIOException("전송 꾸러미를 쓰지 못했습니다.", failed);
        }
    }

    private Map<String, Object> writeScreen(Path worktree, Request request, Screen screen, Path outDir)
            throws IOException {
        String relative = "screens/" + screen.systemCode() + "/" + screen.screenId();
        Path dir = outDir.resolve(relative);
        Files.createDirectories(dir);

        String pages = "core/" + screen.systemCode() + "/pages/" + screen.screenId();
        List<Map<String, Object>> files = new ArrayList<>();
        boolean asIsHtml = copyBlob(worktree, request.asIsCommit(), pages + ".html",
                dir, relative, "as-is.html", "as-is-html", files);
        copyBlob(worktree, request.asIsCommit(), pages + ".md",
                dir, relative, "as-is.md", "as-is-md", files);
        copyBlob(worktree, request.toBeCommit(), pages + ".html",
                dir, relative, "to-be.html", "to-be-html", files);
        copyBlob(worktree, request.toBeCommit(), pages + ".md",
                dir, relative, "to-be.md", "to-be-md", files);

        Path changes = dir.resolve("changes.md");
        Files.writeString(changes, changesBody(screen), StandardCharsets.UTF_8);
        files.add(file(relative + "/changes.md", "changes", changes));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("systemCode", screen.systemCode());
        entry.put("screenId", screen.screenId());
        entry.put("displayName", screen.displayName());
        /*
         * ⭐ 앞판이 없는 것이 「신규 화면」의 유일한 기계적 근거다 — 빈 as-is 파일을 놓으면
         *   개발이 「원래 빈 화면이었다」로 읽는다. 파일을 만들지 않고 이 칸으로 말한다.
         */
        entry.put("newScreen", !asIsHtml);
        entry.put("files", files);
        return entry;
    }

    /** @return 그 판에 파일이 있었나 */
    private boolean copyBlob(Path worktree, String commit, String gitPath, Path dir,
                             String relative, String name, String kind,
                             List<Map<String, Object>> files) throws IOException {
        GitResult shown = git.run(worktree, timeout, "show", commit + ":" + gitPath);
        if (!shown.succeeded()) {
            return false;   // 그 판에 없던 파일 — 신규 화면의 앞판이 여기로 온다
        }
        Path target = dir.resolve(name);
        Files.writeString(target, shown.stdout(), StandardCharsets.UTF_8);
        files.add(file(relative + "/" + name, kind, target));
        return true;
    }

    private static String changesBody(Screen screen) {
        StringBuilder body = new StringBuilder("# " + screen.displayName() + " 변경 목록\n\n");
        if (screen.changes() == null || screen.changes().isEmpty()) {
            // ⚠ 「없음」을 적는다 — 빈 파일은 「아직 안 적었다」와 구분되지 않는다.
            return body.append("- 변경 목록이 비어 있습니다.\n").toString();
        }
        screen.changes().forEach(change -> body.append("- ").append(change).append('\n'));
        return body.toString();
    }

    private static Map<String, Object> file(String path, String kind, Path actual) throws IOException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", path);
        entry.put("kind", kind);
        entry.put("sha256", sha256(Files.readAllBytes(actual)));
        return entry;
    }

    private static String manifest(Request request, List<Map<String, Object>> screens) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"specVersion\": ").append(SPEC_VERSION).append(",\n");
        json.append("  \"request\": {\n")
                .append("    \"label\": ").append(quote(request.label())).append(",\n")
                .append("    \"asIsCommit\": ").append(quote(request.asIsCommit())).append(",\n")
                .append("    \"toBeCommit\": ").append(quote(request.toBeCommit())).append("\n")
                .append("  },\n");
        json.append("  \"screens\": [");
        for (int i = 0; i < screens.size(); i++) {
            json.append(i == 0 ? "\n" : ",\n").append(screenJson(screens.get(i)));
        }
        json.append(screens.isEmpty() ? "]," : "\n  ],").append('\n');
        /*
         * ⭐ expectedBack 은 「목록 밖 화면ID 거절」의 근거다 — 역류가 as-is 재료를 실어 오므로
         *   대조 없이 자리를 지키는 유일한 문 지킴이다(설계 2026-08-24).
         *   ⚠ 지금은 화면 목록만 담는다. 백엔드 모듈은 스냅샷이 버리고 있어 다음 걸음이다.
         */
        json.append("  \"expectedBack\": {\n    \"screens\": [");
        List<String> ids = screens.stream().map(screen -> (String) screen.get("screenId")).toList();
        for (int i = 0; i < ids.size(); i++) {
            json.append(i == 0 ? "" : ", ").append(quote(ids.get(i)));
        }
        json.append("]\n  }\n}\n");
        return json.toString();
    }

    private static String screenJson(Map<String, Object> screen) {
        StringBuilder json = new StringBuilder("    {\n");
        json.append("      \"systemCode\": ").append(quote((String) screen.get("systemCode"))).append(",\n");
        json.append("      \"screenId\": ").append(quote((String) screen.get("screenId"))).append(",\n");
        json.append("      \"displayName\": ").append(quote((String) screen.get("displayName"))).append(",\n");
        json.append("      \"newScreen\": ").append(screen.get("newScreen")).append(",\n");
        json.append("      \"files\": [\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) screen.get("files");
        for (int i = 0; i < files.size(); i++) {
            Map<String, Object> file = files.get(i);
            json.append(i == 0 ? "" : ",\n")
                    .append("        {\"path\": ").append(quote((String) file.get("path")))
                    .append(", \"kind\": ").append(quote((String) file.get("kind")))
                    .append(", \"sha256\": ").append(quote((String) file.get("sha256"))).append('}');
        }
        json.append("\n      ]\n    }");
        return json.toString();
    }

    /** ⚠ 화면ID·시스템코드는 영문·숫자·하이픈이지만 표시 이름은 한글이고 따옴표가 들어올 수 있다. */
    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", impossible);
        }
    }
}
