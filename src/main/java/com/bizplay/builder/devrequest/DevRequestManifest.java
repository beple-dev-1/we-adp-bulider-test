package com.bizplay.builder.devrequest;

import java.util.List;

/**
 * 꾸러미의 <b>지문</b>. {@code manifest.json} 으로 구워진다.
 *
 * <p>전송 설계가 요구한 「보낸 몸의 지문」이 이것이고, {@code dev-request.md} 의 화면별 산출물
 * 목록도 여기서 생성된다 — 그래서 실을지가 선택이 아니다.
 *
 * <p>⭐ <b>{@code specVersion} 이 README 를 대신하는 자리다.</b> 꾸러미 배치가 판을 바꿀 때
 * 개발이 <b>기계로</b> 갈래를 타야 한다 — 사람이 읽는 안내문은 이미 나간 사본이 낡는다.
 *
 * @param specVersion  꾸러미 규격 판. ⛔ 배치를 바꿀 때만 올린다
 * @param devRequestId 사람이 보는 번호({@code DR-003}). ⚠ 프로젝트마다 1번부터라 이것만으로 유일하지 않다
 * @param deliveryKey  이 시도를 가리키는 세상에 하나뿐인 값. <b>다시 보내면 같은 키다</b>
 * @param previousDevRequestId 앞 개발요청서. 사람이 골랐거나 비어 있다
 * @param planningRepoCommit 이 꾸러미가 출발한 기획 저장소 커밋. 없으면 널
 * @param assetRoots   시스템마다 자산을 어디서 떠 왔나. ⛔ 기관 스킨 폴더 이름을 코드가 쥐지 않는다
 * @param files        <b>계약 파일만</b>. ⚠ <b>자기 자신({@code manifest.json})은 없다</b> —
 *                     자기 해시를 자기 안에 담을 길이 없다. 지문을 검사하는 쪽은 이 파일을
 *                     기준으로 삼고 자신은 셈에서 뺀다
 * @param assets       자산 묶음 <b>요약</b>. 낱개로 담지 않는다 — 아래 절
 * @param expectedBack <b>돌려받을 것</b> — 현재 운영 화면 재동기의 대상 표. ⭐ 사람용 계약인
 *                     {@code expected-back.md}와 <b>같은 자료</b>에서 난다
 */
public record DevRequestManifest(String specVersion, String devRequestId, String title,
                                 String deliveryKey, String sentAt, String previousDevRequestId,
                                 String planningRepoCommit, List<String> assetRoots,
                                 List<DevRequestPackage.Entry> files, Assets assets,
                                 DevRequestExpectedBack expectedBack) {

    /**
     * 자산 묶음 요약 — 낱개 대신 이 넷이다.
     *
     * <p>⭐ <b>{@code sha256} 은 자산 묶음 전체의 해시다</b>({@link DevRequestPackage#digestOf}).
     * 개발이 자산 무결성을 재려면 이 한 값으로 잰다.
     *
     * @param count  파일 수. ⭐ {@code dev-request.md} 8절의 「파일 N장」과 <b>같은 기준으로 센 것</b>이다
     * @param bytes  전체 크기
     * @param sha256 묶음 해시
     */
    public record Assets(int count, long bytes, String sha256) {}

    /**
     * 지금 규격 판.
     *
     * <p>⛔ <b>기능을 더할 때 올리지 마라 — 배치가 바뀔 때만이다.</b> 판이 흔들리면 개발이
     * 갈래를 못 탄다.
     */
    public static final String SPEC_VERSION = "2";

    public DevRequestManifest {
        assetRoots = assetRoots == null ? List.of() : List.copyOf(assetRoots);
        files = files == null ? List.of() : List.copyOf(files);
    }

    /**
     * 꾸러미가 담은 파일을 <b>계약과 자산으로 갈라</b> 지문을 만든다.
     *
     * <p>⭐ <b>왜 가르나 (2026-08-25 병주 지시 · 실물 실측).</b> 실제로 나간 꾸러미
     * {@code DRK-0000002} 의 {@code manifest.json} 은 <b>121,497 바이트</b>였고 그중
     * <b>98.7% 가 자산 항목</b>이었다(파일 519 = 자산 514 + 계약 5). 자산 항목을 빼면
     * <b>1,589 바이트</b>다 — <b>76배</b>. 그리고 그 121KB 가 이슈 본문과 함께 개발에게 갔다.
     *
     * <p>⭐ <b>해시가 값을 하는 것은 계약 파일뿐이다.</b> 자산은 추출기 원본을 한 글자도 안 고친
     * 것이라 개발이 낱개로 검증할 이유가 없다. 무결성은 {@link Assets#sha256()} 한 값으로 갚는다.
     *
     * <p>⛔ <b>자산 파일 자체를 꾸러미에서 빼는 쪽으로 가지 마라.</b> 「목업은 혼자 선다」가 계약이고,
     * 그 화면이 무엇을 부르는지 골라내면 <b>놓치는 순간 목업이 깨진다</b>. 여기서 줄이는 것은
     * <b>지문에 적는 줄 수</b>이고 담는 파일은 그대로다.
     *
     * <p>⚠ <b>{@code entries} 는 안 건드린다.</b> zip 과 {@link DevRequestPackage#fingerprint()} 는
     * 자산까지 포함해 셈해야 한다 — 전송 이력이 그 지문으로 「어느 판을 보냈나」를 묶기 때문이다.
     * 여기서 가르는 것은 {@code manifest.json} 에 적는 모양 하나다.
     */
    public static DevRequestManifest of(String devRequestId, String title, String deliveryKey,
                                        String sentAt, String previousDevRequestId,
                                        String planningRepoCommit, List<String> assetRoots,
                                        List<DevRequestPackage.Entry> entries,
                                        DevRequestExpectedBack expectedBack) {
        List<DevRequestPackage.Entry> all = entries == null ? List.of() : entries;
        List<DevRequestPackage.Entry> contract = all.stream()
                .filter(entry -> !DevRequestPackage.isAsset(entry)).toList();
        List<DevRequestPackage.Entry> assetFiles = all.stream()
                .filter(DevRequestPackage::isAsset).toList();
        Assets summary = assetFiles.isEmpty() ? null : new Assets(assetFiles.size(),
                assetFiles.stream().mapToLong(DevRequestPackage.Entry::bytes).sum(),
                DevRequestPackage.digestOf(assetFiles));
        return new DevRequestManifest(SPEC_VERSION, devRequestId, title, deliveryKey, sentAt,
                previousDevRequestId, planningRepoCommit, assetRoots, contract, summary,
                expectedBack);
    }
}
