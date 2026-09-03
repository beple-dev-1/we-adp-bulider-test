package com.bizplay.builder.screenid;

import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.ProjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 표준 화면ID 채번 한 판.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-20-screen-standard-id-design.md}.
 *
 * <p>⭐ <b>최초와 증분이 같은 알고리즘이다.</b> 「없는 화면들을 {@code (pathKey, screenId)} 순으로
 * 세우고 각 묶음의 <b>현재 max 다음부터</b> 붙인다」 하나면 최초에는 max 가 0 이라 {@code 1..N} 이
 * 나오고 증분에는 뒤에 붙는다. ⛔ <b>두 갈래로 가르지 마라</b> — 증분에서 정렬을 다시 돌리면
 * 사전순 중간에 낀 화면 때문에 <b>기존 번호가 전부 한 칸씩 밀린다.</b>
 *
 * <p>⛔ <b>이미 박힌 행을 갱신하지 않는다.</b> 이 클래스에 update 문이 없는 것은 일부러다 —
 * 개발요청서에 이미 찍혀 나간 번호가 다른 화면을 가리키게 된다.
 */
@Service
public class ScreenStandardIdService {

    private static final Logger log = LoggerFactory.getLogger(ScreenStandardIdService.class);
    private static final String UNKNOWN_AREA_CODE = "XXX";

    private final ScreenIdMaterialReader materials;
    private final ScreenStandardIdMapper standardIds;
    private final ScreenIdGroupMapper groups;
    private final BusinessAreaCoder coder;
    private final ProjectMapper projects;
    private final IdSequence ids;
    private final TransactionTemplate transactions;

    public ScreenStandardIdService(ScreenIdMaterialReader materials, ScreenStandardIdMapper standardIds,
                                   ScreenIdGroupMapper groups, BusinessAreaCoder coder,
                                   ProjectMapper projects, IdSequence ids,
                                   TransactionTemplate transactions) {
        this.materials = materials;
        this.standardIds = standardIds;
        this.groups = groups;
        this.coder = coder;
        this.projects = projects;
        this.ids = ids;
        this.transactions = transactions;
    }

    /**
     * 이번에 새로 박은 화면 수.
     *
     * <p>⛔ <b>이 메서드에 {@code @Transactional} 을 걸지 마라.</b> {@code coder.codesOf} 는 실물로
     * 몇 분짜리 AI 호출이다 — {@code ClaudeBusinessAreaCoder} 의 상한은 5분이다.
     * {@code CloneWorker} 가 30분짜리 git clone
     * 앞에서 짚은 것과 같은 함정이다: 트랜잭션 하나가 그 5분을 통째로 물고 있으면 커넥션 풀이
     * 굶고 두 표의 vacuum 이 막힌다. 그래서 <b>읽기 → (트랜잭션 밖에서) AI 호출 → 쓰기</b> 세 토막으로
     * 가른다({@code IaService} 가 {@code TransactionTemplate} 을 쓰는 것과 같은 길이다).
     *
     * <p>⚠ <b>세 토막이라 동시성 구멍이 하나 남는다.</b> 같은 프로젝트에 두 {@code assign} 이
     * 겹치면 둘 다 같은 다음 번호를 읽어 가 같은 {@code standard_id}(또는 같은
     * {@code (area_code, group_no)})를 쓰려 들 수 있다. 그때 유일 제약이 <b>진 쪽의 삽입을
     * 던지게</b> 한다 — 이 메서드는 그 예외를 삼키지 않고 그대로 올린다. <b>이겨서 통과한 트랜잭션은
     * 온전하고, 진 트랜잭션은 자기 몫만(둘 다) 롤백된다</b> — 부분 삽입이 남지 않는다.
     * 호출자(저장소 갱신 흐름)는 이 실패를 「다음 저장소 갱신 때 다시 해 보면 되는 일」로 다룬다.
     */
    public int assign(String projectId, String accountId) {
        // ⚠ 시스템 두 글자가 없는 시스템은 ScreenIdMaterialReader 가 이미 통째로 걸러 낸다
        //   (manifest.json 에 없거나 prefix 가 없는 시스템) — 여기서 다시 거를 것이 없다.
        List<ScreenIdMaterial> numberable = materials.read(projectId);
        if (numberable.isEmpty()) return 0;

        // ⚠ 플랫폼 코드는 프로젝트 열에서 한 번만 읽는다 — 등록 시점에 고정된 값이라
        //   material 마다 다시 조회할 까닭이 없다.
        String platformCode = projects.selectById(projectId).orElseThrow().getPlatformCode();

        Snapshot snapshot = transactions.execute(status -> readSnapshot(projectId, numberable));
        if (snapshot.fresh().isEmpty()) return 0;

        // ⚠ AI 는 트랜잭션 밖에서 부른다 — 위 javadoc 의 까닭 그대로다.
        Map<String, String> minted = snapshot.newAreas().isEmpty()
                ? Map.of()
                : coder.codesOf(projectId, accountId, snapshot.newAreas());

        Integer assigned = transactions.execute(status -> commit(projectId, platformCode, snapshot, minted));
        return assigned;
    }

    /** 읽기 전용 한 토막 — 지금 있는 것들과, 새로 지어야 할 업무영역을 가려 낸다. */
    private Snapshot readSnapshot(String projectId, List<ScreenIdMaterial> numberable) {
        Set<String> already = new HashSet<>();
        Map<String, Integer> maxSeq = new HashMap<>();
        int maxSortNo = 0;
        for (ScreenStandardId row : standardIds.selectByProject(projectId)) {
            already.add(row.screenId());
            maxSortNo = Math.max(maxSortNo, row.sortNo());
            maxSeq.merge(bucketOf(row.standardId()), seqOf(row.standardId()), Math::max);
        }

        List<ScreenIdMaterial> fresh = numberable.stream().filter(m -> !already.contains(m.screenId())).toList();

        Map<String, ScreenIdGroup> groupTable = new LinkedHashMap<>();
        Map<String, String> codeByArea = new LinkedHashMap<>();
        // ⚠ 기능그룹 번호는 "시스템 안에서" 업무영역마다 010 부터 다시 센다 — MRC-010 옆에
        //   CUS-010 이 서는 것이 맞다. 전역 카운터로 두면 둘째 업무영역이 020 부터 시작해
        //   사람이 「왜 010 이 없느냐」고 묻는다.
        // ⚠ 열쇠가 areaKey 하나뿐이면 안 된다(2026-08-20 재확인) — 백오피스의 merchant 와
        //   웹뷰의 merchant 는 같은 업무영역(codeByArea 는 실제로 areaKey 하나로 공유한다)이지만
        //   group_no 카운터는 시스템마다 따로 세야 한다. 안 그러면 웹뷰의 첫 그룹이 백오피스가
        //   이미 쓴 010 다음(020)부터 시작해 표준 ID 를 읽는 사람이 「웹뷰는 왜 010 이 없나」고 묻는다.
        Map<String, Integer> maxGroupNoByArea = new HashMap<>();
        for (ScreenIdGroup group : groups.selectByProject(projectId)) {
            groupTable.put(group.systemCode() + "|" + group.areaKey() + "|" + group.groupKey(), group);
            codeByArea.putIfAbsent(group.areaKey(), group.areaCode());
            maxGroupNoByArea.merge(group.systemCode() + "|" + group.areaKey(), group.groupNo(), Math::max);
        }

        Map<String, String> newAreas = new LinkedHashMap<>();
        for (ScreenIdMaterial material : fresh) {
            if (!codeByArea.containsKey(material.areaKey())) {
                newAreas.putIfAbsent(material.areaKey(), material.areaLabel());
            }
        }

        return new Snapshot(fresh, maxSortNo, maxSeq, groupTable, codeByArea, maxGroupNoByArea, newAreas);
    }

    /** 쓰기 한 토막 — 코드표를 채우고 화면마다 표준ID 를 박는다. */
    private int commit(String projectId, String platformCode, Snapshot snapshot, Map<String, String> minted) {
        Map<String, String> codeByArea = new LinkedHashMap<>(snapshot.codeByArea());
        if (!minted.isEmpty()) {
            Set<String> used = new HashSet<>(codeByArea.values());
            snapshot.newAreas().keySet().forEach(area -> {
                String code = minted.getOrDefault(area, UNKNOWN_AREA_CODE);
                codeByArea.put(area, used.add(code) ? code : UNKNOWN_AREA_CODE);
            });
        }

        Map<String, Integer> maxGroupNoByArea = new HashMap<>(snapshot.maxGroupNoByArea());
        Map<String, ScreenIdGroup> groupTable = new LinkedHashMap<>(snapshot.groupTable());
        for (ScreenIdMaterial material : snapshot.fresh()) {
            String key = groupKeyOf(material);
            if (groupTable.containsKey(key)) continue;
            // ⚠ 경로가 한 마디뿐이면 기능그룹이 없다 — group_no 는 000 이고, 형제 그룹의
            //   카운터를 밀지 않는다(정본 §「경로 1~5단계 가변」· V27 group_no 열 COMMENT).
            int groupNo = material.groupKey().isEmpty()
                    ? 0
                    : maxGroupNoByArea.merge(material.systemCode() + "|" + material.areaKey(), 10, Integer::sum);
            ScreenIdGroup group = new ScreenIdGroup(ids.next(IdSequence.Kind.SCREEN_ID_GROUP),
                    projectId, material.systemCode(), material.areaKey(),
                    codeByArea.getOrDefault(material.areaKey(), UNKNOWN_AREA_CODE), material.areaLabel(),
                    material.groupKey(), groupNo, material.groupLabel());
            groups.insert(group);
            groupTable.put(key, group);
        }

        Map<String, Integer> maxSeq = new HashMap<>(snapshot.maxSeq());
        int sortNo = snapshot.maxSortNo();
        int assigned = 0;
        for (ScreenIdMaterial material : snapshot.fresh()) {
            ScreenIdGroup group = groupTable.get(groupKeyOf(material));
            if (group == null) {
                log.info("업무영역 코드가 없어 건너뛴다 projectId={} screenId={} system={}",
                        projectId, material.screenId(), material.systemCode());
                continue;
            }
            String systemCode2 = material.systemCode2();
            String bucket = "%s-%s-%s-%03d-%s".formatted(platformCode, systemCode2,
                    group.areaCode(), group.groupNo(), material.letter());
            int seq = maxSeq.merge(bucket, 1, Integer::sum);
            String core = StandardScreenIdFormat.core(platformCode, systemCode2,
                    group.areaCode(), group.groupNo(), material.letter(), seq);
            standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID),
                    projectId, material.screenId(), core, ScreenStandardId.Origin.S, ++sortNo));
            assigned++;
        }
        return assigned;
    }

    /**
     * <b>신규 화면</b>({@code tmp-…})에 표준 화면ID 를 박는다 — 개발요청서를 만드는 순간에 부른다.
     *
     * <p>정본: {@code docs/superpowers/specs/2026-08-22-new-screen-id-design.md}. 재료는
     * <b>기준 화면의 시스템·업무영역·기능그룹</b>(그 화면 표준ID 의 앞 네 마디) + <b>이 화면의 유형</b>
     * + 그 묶음의 {@code max + 1}. 유형을 안 골랐으면 기준 화면의 유형 글자를 물려받는다(설계가 말한 「짐작」).
     *
     * <p>⭐ <b>이미 박혀 있으면 그대로 둔다</b> — 되돌린 뒤 다시 완료해도 개발요청서에 찍혀 나간 번호가
     * 안 바뀐다. ⚠ <b>기준 화면에 표준ID 가 없으면 못 박는다</b>(빈 값) — 지어내지 않는다. 전송 전 확인이
     * 그 화면을 막아 사람이 본다.
     *
     * <p>⚠ 앞 네 마디를 자르는 것은 못 2 가 금지한 「분류를 알아내는 파싱」이 아니다 — 다음 번호를 이을
     * <b>자기 표의 앞자리</b>다({@link #bucketOf} 와 같은 까닭).
     *
     * <p>⛔ 트랜잭션은 부르는 쪽 것을 탄다 — 개발요청서 만들기와 한 트랜잭션이어야 번호만 남고 DR 이 없는
     * 상태가 안 생긴다. AI 를 안 부르므로 {@link #assign} 과 달리 트랜잭션 안에 있어도 된다.
     *
     * @param screenType 신규 화면의 유형 한글(목록·상세·등록·수정·안내). 없으면 널
     * @return 박은(또는 이미 있던) 5마디. 기준 화면에 표준ID 가 없어 못 박았으면 빈 값
     */
    public Optional<String> allocateForNewScreen(String projectId, String screenId, String baseScreenId,
                                                 String screenType) {
        List<ScreenStandardId> rows = standardIds.selectByProject(projectId);
        Optional<String> already = rows.stream()
                .filter(row -> row.screenId().equals(screenId)).map(ScreenStandardId::standardId).findFirst();
        if (already.isPresent()) {
            return already;
        }
        if (baseScreenId == null || baseScreenId.isBlank()) {
            return Optional.empty();
        }
        Optional<String> base = rows.stream()
                .filter(row -> row.screenId().equals(baseScreenId)).map(ScreenStandardId::standardId).findFirst();
        if (base.isEmpty()) {
            log.info("기준 화면에 표준 화면ID 가 없어 신규 화면을 채번하지 않는다 projectId={} screenId={} base={}",
                    projectId, screenId, baseScreenId);
            return Optional.empty();
        }
        String baseBucket = bucketOf(base.get());                               // PS-WV-APR-010-L
        String letter = screenType == null || screenType.isBlank()
                ? baseBucket.substring(baseBucket.length() - 1)
                : StandardScreenIdFormat.letterOf("화면", screenType);
        String bucket = baseBucket.substring(0, baseBucket.length() - 1) + letter; // PS-WV-APR-010-D
        int seq = rows.stream().map(ScreenStandardId::standardId)
                .filter(id -> bucketOf(id).equals(bucket))
                .mapToInt(ScreenStandardIdService::seqOf).max().orElse(0) + 1;
        int sortNo = rows.stream().mapToInt(ScreenStandardId::sortNo).max().orElse(0) + 1;
        String core = bucket + "%02d".formatted(seq);
        standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID),
                projectId, screenId, core, ScreenStandardId.Origin.N, sortNo));
        return Optional.of(core);
    }

    /** 메뉴에 직접 배치한 신규 화면은 확정된 IA 경로로 기능그룹을 정한 뒤 번호를 잇는다. */
    public Optional<String> allocateForNewScreenAtMenu(String projectId, String screenId,
                                                       String systemCode, String menuPathKey,
                                                       String screenName, String screenType) {
        if (systemCode == null || menuPathKey == null || menuPathKey.isBlank()) {
            return Optional.empty();
        }

        List<ScreenStandardId> numbered = standardIds.selectByProject(projectId);
        Optional<String> already = numbered.stream()
                .filter(row -> row.screenId().equals(screenId))
                .map(ScreenStandardId::standardId).findFirst();
        if (already.isPresent()) return already;

        // materialize()가 IA에 쓰는 실제 경로는 「선택한 메뉴/신규 화면ID」다. 채번은 그 경로의
        // 앞 두 마디만 쓴다. 메뉴가 업무영역 한 마디뿐이면 신규 화면 자체가 새 기능그룹이 된다.
        String[] keys = (menuPathKey.strip() + "/" + screenId).split("/");
        String areaKey = keys[0];
        String groupKey = keys.length > 1 ? keys[1] : "";
        List<ScreenIdMaterial> source = materials.read(projectId);
        ScreenIdMaterial area = source.stream()
                .filter(material -> systemCode.equals(material.systemCode()))
                .filter(material -> areaKey.equals(material.areaKey()))
                .findFirst().orElse(null);
        if (area == null) {
            log.info("IA 메뉴의 업무영역 채번 재료를 찾지 못했다 projectId={} screenId={} path={}",
                    projectId, screenId, menuPathKey);
            return Optional.empty();
        }

        List<ScreenIdGroup> groupRows = groups.selectByProject(projectId);
        ScreenIdGroup group = groupRows.stream()
                .filter(row -> systemCode.equals(row.systemCode()))
                .filter(row -> areaKey.equals(row.areaKey()))
                .filter(row -> groupKey.equals(row.groupKey()))
                .findFirst().orElse(null);
        if (group == null) {
            ScreenIdGroup areaGroup = groupRows.stream()
                    .filter(row -> systemCode.equals(row.systemCode()))
                    .filter(row -> areaKey.equals(row.areaKey()))
                    .findFirst().orElse(null);
            if (areaGroup == null) {
                log.info("IA 메뉴의 업무영역 코드가 없어 신규 화면을 채번하지 않는다 projectId={} screenId={} path={}",
                        projectId, screenId, menuPathKey);
                return Optional.empty();
            }
            int groupNo = groupKey.isEmpty() ? 0 : groupRows.stream()
                    .filter(row -> systemCode.equals(row.systemCode()))
                    .filter(row -> areaKey.equals(row.areaKey()))
                    .mapToInt(ScreenIdGroup::groupNo).max().orElse(0) + 10;
            String groupLabel = groupKey.equals(screenId) && screenName != null && !screenName.isBlank()
                    ? screenName.strip()
                    : source.stream()
                    .filter(material -> systemCode.equals(material.systemCode()))
                    .filter(material -> areaKey.equals(material.areaKey()))
                    .filter(material -> groupKey.equals(material.groupKey()))
                    .map(ScreenIdMaterial::groupLabel).filter(Objects::nonNull)
                    .findFirst().orElse(groupKey);
            group = new ScreenIdGroup(ids.next(IdSequence.Kind.SCREEN_ID_GROUP), projectId,
                    systemCode, areaKey, areaGroup.areaCode(), area.areaLabel(),
                    groupKey, groupNo, groupLabel);
            groups.insert(group);
        }

        String platformCode = projects.selectById(projectId).orElseThrow().getPlatformCode();
        String letter = StandardScreenIdFormat.letterOf("화면", screenType);
        String bucket = "%s-%s-%s-%03d-%s".formatted(platformCode, area.systemCode2(),
                group.areaCode(), group.groupNo(), letter);
        int seq = numbered.stream().map(ScreenStandardId::standardId)
                .filter(id -> bucketOf(id).equals(bucket))
                .mapToInt(ScreenStandardIdService::seqOf).max().orElse(0) + 1;
        int sortNo = numbered.stream().mapToInt(ScreenStandardId::sortNo).max().orElse(0) + 1;
        String core = bucket + "%02d".formatted(seq);
        standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID),
                projectId, screenId, core, ScreenStandardId.Origin.N, sortNo));
        return Optional.of(core);
    }

    private static String groupKeyOf(ScreenIdMaterial material) {
        return material.systemCode() + "|" + material.areaKey() + "|" + material.groupKey();
    }

    /**
     * 5마디에서 「같은 묶음」을 가르는 앞부분을 뗀다 — {@code PS-BO-MRC-010-L}.
     *
     * <p>⚠ <b>이것은 §1 의 못 2 가 금지한 「분류를 알아내는 파싱」이 아니다.</b> 여기서 읽는 것은
     * 다음 일련번호를 잇기 위한 <b>자기 표의 앞자리</b>이지 업무영역의 뜻이 아니다.
     */
    private static String bucketOf(String standardId) {
        int lastDash = standardId.lastIndexOf('-');
        return standardId.substring(0, lastDash + 2);
    }

    private static int seqOf(String standardId) {
        int lastDash = standardId.lastIndexOf('-');
        return Integer.parseInt(standardId.substring(lastDash + 2));
    }

    /** 읽기 토막과 쓰기 토막 사이, AI 호출 동안 손에 쥐고 있을 스냅샷. */
    private record Snapshot(
            List<ScreenIdMaterial> fresh,
            int maxSortNo,
            Map<String, Integer> maxSeq,
            Map<String, ScreenIdGroup> groupTable,
            Map<String, String> codeByArea,
            Map<String, Integer> maxGroupNoByArea,
            Map<String, String> newAreas) {
    }
}
