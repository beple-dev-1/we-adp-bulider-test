package com.bizplay.builder.design;

import com.bizplay.builder.project.PlanningManifestReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code core/<시스템>/styleguide.md} 의 <b>어휘 울타리</b>를 읽는 한 자리.
 *
 * <p>⭐ <b>왜 이 파일인가.</b> 「AI 가 신규화면을 쓸 때 class 이름을 지어내지 않는다」의 재료는
 * {@code design-index.json} 이 아니라 여기다. 실물 {@code webview/styleguide.md} 는 1207줄에
 * class 1150종을 들고 있고, 소스에 출하된 오타 {@code ui-fex-1} 이 그 안에 있다
 * ({@code ui-flex-1} 은 소스에 <b>없다</b> — 「올바르게」 쓰면 스타일이 한 줄도 안 먹는다).
 *
 * <p>⚠ <b>검사기 {@code A-5} 가 같은 울타리를 읽는다.</b> 그래서 이 목록 밖의 class 를 쓰면
 * 기획 레포가 red 가 된다 — 이 화면은 그 red 를 미리 보는 자리다.
 *
 * <p>⛔ <b>울타리 밖을 읽지 마라.</b> 울타리 밖에는 기획자가 쓰는 해설층이 있고, 그것을
 * 목록으로 삼으면 사람이 쓴 산문이 어휘로 올라간다. 울타리가 없으면 <b>빈 집합</b>이다 —
 * 그것이 검사기가 보는 것과 같은 상태다(그때 A-5 는 모든 class 를 red 로 본다).
 */
@Component
public class StyleVocabularyReader {

    private static final Logger log = LoggerFactory.getLogger(StyleVocabularyReader.class);

    /**
     * 울타리 둘.
     *
     * <p>⚠ <b>{@code unstyled} 는 {@code classes} 의 형제다 — 안에 든 것이 아니다.</b>
     * 실물 여섯 장 전수가 그 모양이다({@code webview} 는 {@code classes:end} 1120줄 뒤에
     * {@code unstyled:begin} 이 1132줄에 온다). 「안에 들었다」로 읽으면 <b>스타일 없는 이름을
     * 통째로 놓친다</b> — 2026-08-22 실물 탐침이 그 고장을 잡았다(씨앗만으로는 안 잡혔다.
     * 씨앗을 내가 중첩으로 지었기 때문이다).
     *
     * <p>다만 <b>중첩도 견딘다</b> — {@code classes} 안에서 발견되면 거기서 떼어낸다.
     * 규격이 그 모양을 금지하지 않아서, 그 레포를 만나도 이름이 섞이지 않게 한다.
     */
    private static final Pattern CLASSES = fence("classes");
    private static final Pattern UNSTYLED = fence("unstyled");

    /** {@code - `이름`} 한 줄. 백틱 안만 집는다 — 주석의 산문에 걸리지 않게. */
    private static final Pattern ITEM = Pattern.compile("(?m)^\\s*-\\s+`([^`]+)`\\s*$");

    private final PlanningManifestReader manifests;

    public StyleVocabularyReader(PlanningManifestReader manifests) {
        this.manifests = manifests;
    }

    public StyleVocabulary read(String projectId, String system) {
        Optional<Path> file = manifests.styleguideFile(projectId, system);
        if (file.isEmpty() || !Files.isRegularFile(file.get())) {
            log.info("styleguide 가 없다 projectId={} system={}", projectId, system);
            return StyleVocabulary.empty();
        }
        String body;
        try {
            body = Files.readString(file.get());
        } catch (IOException | RuntimeException unreadable) {
            log.info("styleguide 를 읽지 못했다 projectId={} system={}", projectId, system);
            return StyleVocabulary.empty();
        }
        // ⭐ 스타일 없는 이름은 문서 전체에서 찾는다 — 형제로 놓이는 것이 실물의 모양이다.
        Matcher unstyled = UNSTYLED.matcher(body);
        List<String> withoutStyle = unstyled.find() ? itemsOf(unstyled.group(1)) : List.of();

        Matcher classes = CLASSES.matcher(body);
        if (!classes.find()) {
            // 허용목록 울타리가 없으면 허용목록은 빈 집합이다 — 검사기 A-5 가 보는 것과 같다.
            return new StyleVocabulary(List.of(), withoutStyle);
        }
        String allowed = classes.group(1);

        // ⚠ 중첩된 레포도 견딘다 — 안에 들었으면 떼어내야 이름이 허용목록에 섞이지 않는다.
        Matcher nested = UNSTYLED.matcher(allowed);
        if (nested.find()) {
            allowed = allowed.substring(0, nested.start()) + allowed.substring(nested.end());
        }
        return new StyleVocabulary(itemsOf(allowed), withoutStyle);
    }

    private static List<String> itemsOf(String block) {
        List<String> found = new ArrayList<>();
        Matcher item = ITEM.matcher(block);
        while (item.find()) {
            found.add(item.group(1));
        }
        return List.copyOf(found);
    }

    private static Pattern fence(String name) {
        return Pattern.compile("<!--\\s*" + name + ":begin\\s*-->(.*?)<!--\\s*" + name + ":end\\s*-->",
                Pattern.DOTALL);
    }

    /**
     * 그 시스템의 class 어휘.
     *
     * @param allowed  소스 css 에 선택자가 <b>있는</b> 이름들. 검사기 A-5 가 통과시키는 집합이다
     * @param unstyled 목업이 쓰지만 소스 css 에서 <b>선택자를 못 찾은</b> 이름들.
     *                 ⚠ <b>여기에 우리가 판정을 붙이지 않는다.</b> 그 칸은 추출기가
     *                 「사람이 검토한 예외가 아니다」라고 못 박아 뒀고, 뜻이 「쓰지 마라」인지
     *                 「아직 판정 전」인지 물어 둔 상태다 — 사실만 적는다
     */
    public record StyleVocabulary(List<String> allowed, List<String> unstyled) {

        public static StyleVocabulary empty() {
            return new StyleVocabulary(List.of(), List.of());
        }

        public boolean isEmpty() {
            return allowed.isEmpty() && unstyled.isEmpty();
        }

        public int total() {
            return allowed.size() + unstyled.size();
        }
    }
}
