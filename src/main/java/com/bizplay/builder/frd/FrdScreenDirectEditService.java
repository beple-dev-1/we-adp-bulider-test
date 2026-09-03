package com.bizplay.builder.frd;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Range;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** FRD 미리보기에서 선택한 단일 요소의 문구만 바꾸고 새 화면 버전으로 보존한다. */
@Service
public class FrdScreenDirectEditService {

    private static final int MAX_SELECTOR_LENGTH = 2_000;
    private static final int MAX_TEXT_LENGTH = 1_000;
    private static final Set<String> EDITABLE_TAGS = Set.of(
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "span", "strong", "b", "em", "i",
            "small", "u", "s", "sub", "sup", "label", "button", "a", "th", "td", "li", "dt", "dd",
            "legend", "caption", "option", "summary");

    private final FrdMapper frds;
    private final FrdScreenFiles screenFiles;
    private final ScreenMockupService mockups;

    public FrdScreenDirectEditService(FrdMapper frds, FrdScreenFiles screenFiles, ScreenMockupService mockups) {
        this.frds = frds;
        this.screenFiles = screenFiles;
        this.mockups = mockups;
    }

    @Transactional
    public Result edit(String projectId, String frdId, FrdScreen screen,
                       String selector, String expectedText, String newText) {
        if (screen.state() == FrdScreen.State.GENERATING) {
            throw new IllegalStateException("AI 초안을 만드는 중에는 문구를 직접 수정할 수 없습니다.");
        }
        String checkedSelector = required(selector, "수정할 화면 요소를 찾지 못했습니다.");
        if (checkedSelector.length() > MAX_SELECTOR_LENGTH) {
            throw new IllegalArgumentException("선택한 화면 요소의 경로가 너무 깁니다. 더 작은 문구를 선택해 주세요.");
        }
        String checkedText = required(newText, "바꿀 문구를 입력해 주세요.");
        if (checkedText.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("문구는 1,000자 이내로 입력해 주세요.");
        }

        Frd frd = frds.selectById(frdId);
        String systemCode = screen.systemCode() == null || screen.systemCode().isBlank()
                ? (frd == null ? null : frd.systemCode()) : screen.systemCode();
        Path target = targetOf(projectId, frdId, screen, systemCode);
        String before = readTarget(target);
        Document document = Jsoup.parse(before, "", Parser.htmlParser().setTrackPosition(true));
        Elements matched;
        try {
            matched = document.select(checkedSelector);
        } catch (RuntimeException invalidSelector) {
            throw new IllegalArgumentException("수정할 화면 요소를 찾지 못했습니다.");
        }
        if (matched.size() != 1) {
            throw new IllegalArgumentException("수정할 문구 하나를 정확히 찾지 못했습니다. 화면에서 다시 선택해 주세요.");
        }
        Element element = matched.first();
        if (element == null || !EDITABLE_TAGS.contains(element.tagName().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("첫 버전에서는 제목, 버튼, 안내 문구 같은 텍스트만 직접 수정할 수 있습니다.");
        }
        List<TextNode> directTextNodes = element.childNodes().stream()
                .filter(TextNode.class::isInstance).map(TextNode.class::cast)
                .filter(node -> !normalized(node.getWholeText()).isBlank())
                .toList();
        List<TextNode> textNodes = directTextNodes.stream()
                .filter(node -> normalized(node.getWholeText()).equals(normalized(expectedText)))
                .toList();
        if (textNodes.size() != 1) {
            if (directTextNodes.size() == 1) {
                throw new IllegalStateException("화면 문구가 이미 바뀌었습니다. 화면을 다시 연 뒤 수정해 주세요.");
            }
            throw new IllegalArgumentException("수정할 문구 하나를 정확히 찾지 못했습니다. 화면에서 다시 선택해 주세요.");
        }
        TextNode textNode = textNodes.get(0);
        String currentText = textNode.getWholeText();
        if (!normalized(currentText).equals(normalized(expectedText))) {
            throw new IllegalStateException("화면 문구가 이미 바뀌었습니다. 화면을 다시 연 뒤 수정해 주세요.");
        }
        if (normalized(currentText).equals(normalized(checkedText))) {
            throw new IllegalArgumentException("기존 문구와 다른 내용을 입력해 주세요.");
        }

        Range textRange = textNode.sourceRange();
        if (!textRange.isTracked() || textRange.startPos() > textRange.endPos()) {
            throw new IllegalStateException("수정할 문구의 원문 위치를 확인하지 못했습니다.");
        }
        String replacement = leadingWhitespace(currentText)
                + Entities.escape(checkedText, document.outputSettings()) + trailingWhitespace(currentText);
        String updated = before.substring(0, textRange.startPos()) + replacement
                + before.substring(textRange.endPos());
        String change = "문구 직접 수정: %s → %s".formatted(shortText(currentText), shortText(checkedText));
        try {
            Files.writeString(target, updated, StandardCharsets.UTF_8);
            long historyId = mockups.markGenerated(screen.id(),
                    new ScreenMockupReader.Mockup(updated, List.of(change)));
            return new Result(historyId, checkedSelector, checkedText, change);
        } catch (IOException failure) {
            IllegalStateException rejected = new IllegalStateException(
                    "수정한 화면 문구를 작업공간에 저장하지 못했습니다.", failure);
            restore(target, before, rejected);
            throw rejected;
        } catch (RuntimeException failure) {
            restore(target, before, failure);
            throw failure;
        }
    }

    private Path targetOf(String projectId, String frdId, FrdScreen screen, String systemCode) {
        if (systemCode == null || systemCode.isBlank()) {
            throw new IllegalStateException("수정할 화면 파일 경로를 확인하지 못했습니다.");
        }
        Path target = screenFiles.existingHtml(projectId, frdId, systemCode,
                screen.screenId(), screen.facet());
        if (target == null || !Files.isRegularFile(target)) {
            throw new IllegalStateException("수정할 화면 파일을 작업공간에서 찾지 못했습니다.");
        }
        return target;
    }

    private String readTarget(Path target) {
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("수정할 화면 파일을 읽지 못했습니다.", failure);
        }
    }

    private void restore(Path target, String before, RuntimeException failure) {
        try {
            Files.writeString(target, before, StandardCharsets.UTF_8);
        } catch (IOException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    private static String required(String value, String message) {
        String checked = value == null ? "" : value.strip();
        if (checked.isBlank()) throw new IllegalArgumentException(message);
        return checked;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String leadingWhitespace(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return value.substring(0, index);
    }

    private static String trailingWhitespace(String value) {
        int index = value.length();
        while (index > 0 && Character.isWhitespace(value.charAt(index - 1))) index--;
        return value.substring(index);
    }

    private static String shortText(String value) {
        String oneLine = normalized(value);
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }

    public record Result(long historyId, String selector, String text, String change) { }
}
