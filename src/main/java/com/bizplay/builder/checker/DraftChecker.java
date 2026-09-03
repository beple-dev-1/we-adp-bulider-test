package com.bizplay.builder.checker;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 저장 전 검사 — <b>「누구 탓인가」를 가른다.</b>
 *
 * <p>⭐ <b>왜 전체를 두 번 돌리나.</b> 2026-08-14 에 실물 기획 레포를 클론해 재보니
 * <b>이미 red 26 · review 17 이 깔려 있었다.</b> 그러니 「전체가 초록이어야 저장」은 <b>애초에 불가능</b>하다 —
 * 남이 만든 빨강이 기획자를 영원히 막는다. 그리고 검사기에는 <b>한 파일만 보는 입구가 없다</b>
 * (인자는 {@code [--json] [<뿌리>]} 뿐).
 *
 * <p><b>그래서 얹기 전과 후를 견줘 새로 생긴 것만 그 사람 탓으로 돌린다.</b> 전체 검사가
 * <b>~0.95초</b>라 두 번 돌려도 2초다(263화면 실측) — 추출기에 새 기능을 요청하지 않아도 된다.
 *
 * <p>⛔ <b>「그 파일만 본다」로 바꾸지 마라.</b> 앵커 전집·짝 파일·색인 일치는
 * <b>레포 전체와의 관계</b>를 보는 검사다. 파일 내용만 따로 재면 그 셋을 전부 놓친다.
 */
@Component
public class DraftChecker {

    private final CheckerCommand checker;

    public DraftChecker(CheckerCommand checker) {
        this.checker = checker;
    }

    /**
     * 후보를 그 자리에 얹은 셈으로 검사한다. <b>작업 자리는 검사 뒤에 원래대로 돌아간다.</b>
     *
     * @param checkerHome   검사기가 <b>설치된</b> 자리(= 공용 클론). ⚠ 워크트리에는 {@code node_modules} 가
     *                      안 딸려오므로 여기를 따로 받는다 — 안 그러면 검사마다 {@code npm install} 을 한다
     * @param workspaceRoot 이 검사에만 쓰는 레포 사본(워크트리). ⛔ <b>공용 클론을 주지 마라</b> —
     *                      두 사람이 동시에 저장하면 서로의 초안이 섞여 차이 판정이 거짓이 된다
     * @param repoRelativePath 레포 뿌리 기준 자리. {@code /} 로 잇는다(개발은 윈도우 · 운영은 리눅스)
     */
    public DraftCheckResult check(Path checkerHome, Path workspaceRoot,
                                  String repoRelativePath, String content) {
        Path target = resolveInside(workspaceRoot, repoRelativePath);

        // ① 얹기 전 — 남이 깔아 둔 것이 무엇인지 먼저 안다.
        CheckReport before = checker.run(checkerHome, workspaceRoot);

        byte[] original = readIfExists(target);
        try {
            write(target, content);
            // ② 얹은 뒤.
            CheckReport after = checker.run(checkerHome, workspaceRoot);
            return compare(before, after);
        } finally {
            // ⛔ 되돌리기를 finally 에 둔다. 안 되돌리면 그 자리에 남의 초안이 남아
            //    **다음 사람의 「얹기 전」이 내 초안을 포함한 상태**가 된다 — 차이 판정이 통째로 거짓이 된다.
            restore(target, original);
        }
    }

    /**
     * ⚠ <b>「못 냈다」가 이긴다.</b> 어느 한쪽이라도 판정을 못 냈으면 견줄 것이 없다 —
     * 그때 초록을 내면 <b>검사 없이 저장이 열린다.</b>
     */
    private static DraftCheckResult compare(CheckReport before, CheckReport after) {
        if (before.isUnknown() || after.isUnknown()) {
            return new DraftCheckResult(DraftCheckResult.Verdict.UNKNOWN, List.of(), List.of());
        }
        List<Finding> caused = onlyIn(after, before);
        List<Finding> fixed = onlyIn(before, after);
        return new DraftCheckResult(judge(caused), caused, fixed);
    }

    /** ⚠ 빨강이 하나라도 새로 생기면 빨강이다 — 확인 항목이 섞여 있어도 그렇다. */
    private static DraftCheckResult.Verdict judge(List<Finding> caused) {
        if (caused.isEmpty()) {
            return DraftCheckResult.Verdict.GREEN;
        }
        boolean anyRed = caused.stream().anyMatch(it -> it.level() == Finding.Level.RED);
        return anyRed ? DraftCheckResult.Verdict.RED : DraftCheckResult.Verdict.REVIEW_REQUIRED;
    }

    private static List<Finding> onlyIn(CheckReport source, CheckReport other) {
        Set<String> otherKeys = new LinkedHashSet<>();
        other.findings().forEach(it -> otherKeys.add(it.key()));
        return source.findings().stream().filter(it -> !otherKeys.contains(it.key())).toList();
    }

    /**
     * ⛔ <b>레포 밖을 가리키는 자리를 거절한다.</b> 자리 글자가 주소·화면에서 오므로
     * {@code ..} 가 그냥 통과하면 <b>레포 밖 파일을 덮어쓴다.</b>
     * {@code ProjectPaths} 가 프로젝트 번호에서 이미 같은 문을 세웠다 — 같은 함정이다.
     */
    private static Path resolveInside(Path workspaceRoot, String repoRelativePath) {
        if (repoRelativePath == null || repoRelativePath.isBlank()) {
            throw new IllegalArgumentException("검사할 자리가 없다");
        }
        // ⚠ 레포 기준 자리는 「/」 로만 잇는다. 역슬래시를 받아 주면 윈도우와 리눅스에서
        //    같은 글자가 다른 자리를 뜻하게 된다(decided-facts 6번).
        if (repoRelativePath.contains("\\")) {
            throw new IllegalArgumentException("레포 안 자리는 / 로 잇는다: " + repoRelativePath);
        }
        Path relative = Path.of(repoRelativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("레포 안 자리가 아니다: " + repoRelativePath);
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("레포 밖을 가리킨다: " + repoRelativePath);
        }
        return resolved;
    }

    private static byte[] readIfExists(Path target) {
        try {
            return Files.exists(target) ? Files.readAllBytes(target) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 없던 파일이었으면 되돌리기는 <b>지우는 것</b>이다. */
    private static void restore(Path target, byte[] original) {
        try {
            if (original == null) {
                Files.deleteIfExists(target);
            } else {
                Files.write(target, original);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
