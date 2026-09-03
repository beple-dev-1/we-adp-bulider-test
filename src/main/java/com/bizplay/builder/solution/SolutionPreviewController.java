package com.bizplay.builder.solution;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 추출된 운영 화면(as-is) 파일을 미리보기로 내준다.
 *
 * <p><b>주소를 레포 배치와 같은 모양으로 짓는다.</b> 추출된 html 이 곁의 css·이미지를
 * {@code ../assets/css/style.css} 처럼 <b>상대 경로로</b> 부르는데, 주소가 같은 모양이면
 * 그 상대 경로가 저절로 맞는다 — html 을 고쳐 쓸 일이 없다.
 *
 * <pre>
 * 레포   core/backoffice/pages/bo-bizcard-list.html
 * 주소   /projects/0000001/artifacts/solution-mockups/files/backoffice/pages/bo-bizcard-list.html
 * </pre>
 *
 * <p>⛔ <b>막을 것 셋. 하나라도 빼지 마라.</b>
 * <ol>
 *   <li><b>클론 밖으로 못 나간다</b> — {@code ..} 을 정리한 뒤 실경로로 다시 재서
 *       {@code core/} 밑인지 확인한다. 심볼릭 링크로도 못 빠져나간다</li>
 *   <li><b>확장자 흰 목록만 낸다</b> — 화면 md 는 미리보기가 아니다. 색인·설정도 안 낸다</li>
 *   <li><b>스크립트를 못 돌린다</b> — 추출 html 285장 중 <b>225장에 {@code <script>} 가
 *       남아 있다</b>(2026-08-16 실측). 남의 소스에서 온 스크립트를 우리 화면 자격으로
 *       돌리지 않는다. 화면 쪽 {@code iframe sandbox} 와 <b>둘 다</b> 건다 —
 *       새 창으로 열면 {@code iframe} 이 없어서 한쪽만으로는 안 막힌다</li>
 * </ol>
 *
 * @see #SANDBOX
 */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/solution-mockups/files")
public class SolutionPreviewController {

    /**
     * 이 문의 주소 모양 — {@code SecurityConfig} 가 {@code X-Frame-Options} 를 여기서만
     * 느슨하게 하려고 쓴다.
     *
     * <p>⛔ <b>위의 {@code @RequestMapping} 과 따로 놀게 두지 마라.</b> 둘이 갈리면
     * 미리보기 칸이 <b>말없이</b> 다시 빈다 — 서버는 200 을 내고 브라우저만 안 그린다.
     */
    public static final String URL_PATTERN = "/projects/*/artifacts/solution-mockups/files/**";

    /**
     * 미리보기를 가두는 울타리 — 화면의 {@code iframe sandbox} 와 <b>같은 글자여야 한다.</b>
     *
     * <p>⭐ <b>{@code allow-same-origin} 이 없으면 곁의 css 가 안 붙는다</b>(2026-08-17 실측).
     * 출처가 없는(opaque) 문서가 되면 크롬이 곁딸린 요청을 남의 사이트 것으로 쳐서
     * {@code JSESSIONID} 를 안 붙이고, 시큐리티가 {@code 302 → /login} 으로 되튕긴다.
     * <b>뼈대만 뜨고 스타일이 통째로 벗겨진 화면</b>이 그것이다.
     *
     * <p>⛔ <b>여기에 {@code allow-scripts} 를 더하지 마라. 하나라도 예외 두지 마라.</b>
     * {@code allow-same-origin} 과 {@code allow-scripts} 가 <b>같이</b> 있으면 안의 문서가
     * 제 울타리를 스스로 걷어낼 수 있다 — 그때 남의 스크립트가 <b>우리 자격</b>으로 돌고
     * 우리 쿠키를 읽는다. 지금은 스크립트가 아예 못 도니 {@code allow-same-origin} 하나는 안전하다.
     */
    public static final String SANDBOX = "allow-same-origin";

    /**
     * 내줄 수 있는 확장자와 그 종류.
     *
     * <p>⛔ 여기에 {@code md}·{@code json}·{@code yml} 을 더하지 마라 — 화면 명세와 색인은
     * 미리보기가 아니라 <b>빌더가 읽어 화면으로 만드는 재료</b>다. 날것으로 내주기 시작하면
     * 저장소 통째로가 정적 서버가 된다.
     */
    private static final Map<String, String> SERVABLE = Map.ofEntries(
            Map.entry("html", "text/html;charset=UTF-8"),
            Map.entry("css", "text/css;charset=UTF-8"),
            Map.entry("js", "text/javascript;charset=UTF-8"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("eot", "application/vnd.ms-fontobject"));

    private final SolutionScreenReader reader;
    private final SkinRewriter skins;

    public SolutionPreviewController(SolutionScreenReader reader, SkinRewriter skins) {
        this.reader = reader;
        this.skins = skins;
    }

    /**
     * @param skin 어느 기관으로 그리나. <b>html 일 때만</b> 쓴다 — 곁딸린 css·이미지는 이미 갈린
     *             주소로 들어오므로 <b>그대로 낸다.</b> 없으면 파일 그대로다
     *             ({@code SkinRewriter} · 설계 §3 의 「못 정하면 안 갈아낀다」)
     */
    @GetMapping("/**")
    public ResponseEntity<?> file(@PathVariable String projectId,
                                  @RequestParam(required = false) String skin,
                                  HttpServletRequest request) {
        String relative = relativePath(projectId, request);
        Path core = realPath(reader.coreRoot(projectId));
        Path target = resolveInside(core, relative);

        String extension = extensionOf(target);
        String type = SERVABLE.get(extension);
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "미리보기로 낼 수 있는 파일이 아니다");
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                // ⛔ 확장자로 정한 종류를 브라우저가 다시 짐작하지 못하게 막는다.
                .header("X-Content-Type-Options", "nosniff")
                // ⛔ 새 창으로 열어도 스크립트가 안 돌게 한다 — iframe sandbox 는 그때 없다.
                .header("Content-Security-Policy", "sandbox " + SANDBOX)
                .header("Content-Type", type);

        if ("html".equals(extension) && skin != null && !skin.isBlank()) {
            return response.body(skins.draw(projectId, repoDirOf(relative), skin, read(target)));
        }
        return response.body(new FileSystemResource(target));
    }

    /**
     * 미리보기 주소의 자리를 <b>저장소 뿌리 기준 폴더</b>로 되돌린다 —
     * {@code webview/pages/wv-x.html} → {@code core/webview/pages}.
     *
     * <p>⚠ 치환은 그 폴더를 기준으로 상대경로를 푼다. 여기가 틀리면 조용히 아무것도 안 갈린다.
     */
    private static String repoDirOf(String relative) {
        int fileAt = relative.lastIndexOf('/');
        return fileAt < 0 ? "core" : "core/" + relative.substring(0, fileAt);
    }

    private static String read(Path target) {
        try {
            return Files.readString(target, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 파일이 없다");
        }
    }

    // ── 울타리 ────────────────────────────────────────────────────────────

    /** 주소에서 {@code files/} 뒤의 나머지를 뗀다. */
    private String relativePath(String projectId, HttpServletRequest request) {
        Object matched = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String full = matched == null ? request.getRequestURI() : matched.toString();
        String prefix = "/projects/%s/artifacts/solution-mockups/files/".formatted(projectId);
        int at = full.indexOf(prefix);
        if (at < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "미리보기 주소가 아니다");
        }
        return full.substring(at + prefix.length());
    }

    /**
     * 클론의 {@code core/} 안쪽인지 실경로로 다시 잰다.
     *
     * <p>⛔ <b>{@code normalize()} 만으로 끝내지 마라.</b> 그것은 글자를 정리할 뿐이라
     * 심볼릭 링크로 나가는 길이 남는다 — 클론은 우리가 만든 것이 아니라 남의 레포다.
     */
    private Path resolveInside(Path core, String relative) {
        if (relative.isBlank() || relative.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 파일이 없다");
        }
        Path target;
        try {
            target = core.resolve(relative).normalize();
        } catch (InvalidPathException bad) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 파일이 없다");
        }
        if (!target.startsWith(core) || !Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 파일이 없다");
        }
        Path real = realPath(target);
        if (!real.startsWith(core)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 파일이 없다");
        }
        return real;
    }

    private Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 파일이 없다");
        }
    }

    private String extensionOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }
}
