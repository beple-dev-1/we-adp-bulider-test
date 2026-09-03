package com.bizplay.builder.devrequest;

import java.nio.file.Path;
import java.util.List;

/**
 * 개발에 나가는 <b>꾸러미 한 채</b> — 디벨롭과의 계약서다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}.
 *
 * <p>⛔ <b>{@code README} 를 넣지 않는다.</b> 어떤 파일이 무엇인가는 {@code dev-request.md} 의
 * 화면별 산출물 목록이, 기계가 읽을 목록은 {@code manifest.json} 이, 어떻게 받아 처리하나는
 * 창구 계약({@code docs/requests-to-dev.md})이 답한다. README 는 그 셋과 갈리는 넷째 사본이 되고,
 * DR 마다 같은 글을 N벌 복사해서 배치가 바뀌면 <b>이미 나간 N벌이 전부 거짓</b>이 된다.
 *
 * @param root    꾸러미 뿌리
 * @param entries 꾸러미가 담은 파일. <b>순서가 곧 {@code dev-request.md} 의 목록 순서</b>다
 * @param archive 개발팀에 보내고 다운로드에도 쓰는 저장 ZIP. 아직 저장 전이면 널이다
 */
public record DevRequestPackage(Path root, List<Entry> entries, Path archive) {

    public DevRequestPackage(Path root, List<Entry> entries) {
        this(root, entries, null);
    }

    public DevRequestPackage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public DevRequestPackage withArchive(Path storedArchive) {
        return new DevRequestPackage(root, entries, storedArchive);
    }

    /**
     * 꾸러미 안 파일 한 장.
     *
     * @param path        꾸러미 뿌리 기준 상대 경로. ⚠ 구분자는 언제나 {@code /} 다 —
     *                    윈도에서 구운 것을 리눅스에서 읽는다
     * @param description 이것이 무엇인가 한 줄. ⭐ README 가 하려던 일 중 실제로 필요한 것이 이것 하나다
     * @param bytes       크기
     * @param sha256      지문. 사본 둘이 돌 때 어느 것이 진짜인지 가른다
     */
    public record Entry(String path, String description, long bytes, String sha256) {}

    public long totalBytes() {
        return entries.stream().mapToLong(Entry::bytes).sum();
    }

    /**
     * 꾸러미 한 채의 지문 — 파일별 해시를 <b>경로 순서로</b> 묶어 한 번 더 해싱한다.
     *
     * <p>⭐ <b>전송 이력이 이 값을 남긴다.</b> 「받았다」가 <b>어느 판을 받은 것인지</b> 안 묶이면,
     * 그 사이 문서가 바뀌었을 때 개발이 무엇을 받았는지 아무도 모른다.
     *
     * <p>⚠ <b>경로로 정렬한다</b> — 파일을 담은 순서가 바뀌어도 같은 꾸러미면 같은 지문이어야 한다.
     */
    public String fingerprint() {
        return digestOf(entries);
    }

    /**
     * 자산인가 — <b>가르는 기준은 이 자리 하나다.</b>
     *
     * <p>⛔ <b>{@code path().contains("/assets/")} 를 다른 곳에 또 적지 마라.</b> 8절이 세는 자산과
     * {@code manifest.json} 이 요약하는 자산이 갈리면 <b>사람이 읽는 수와 기계가 읽는 수가 달라진다.</b>
     */
    public static boolean isAsset(Entry entry) {
        return entry.path().contains("/assets/");
    }

    /**
     * 파일 묶음 하나의 해시 — 파일별 해시를 <b>경로 순서로</b> 묶어 한 번 더 해싱한다.
     *
     * <p>⭐ 꾸러미 전체({@link #fingerprint()})와 <b>자산 묶음</b>이 같은 셈법을 쓴다.
     * {@code manifest.json} 이 자산 514장을 낱개로 담는 대신 이 값 하나로 갚기 때문이다.
     */
    public static String digestOf(List<Entry> group) {
        String joined = group.stream()
                .sorted(java.util.Comparator.comparing(Entry::path))
                .map(entry -> entry.path() + ":" + entry.sha256())
                .reduce("", (left, right) -> left + right + "\n");
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte one : digest) {
                out.append(Character.forDigit((one >> 4) & 0xF, 16));
                out.append(Character.forDigit(one & 0xF, 16));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", impossible);
        }
    }
}
