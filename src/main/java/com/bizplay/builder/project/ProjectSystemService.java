package com.bizplay.builder.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 프로젝트의 시스템 등록 — 코드는 레포에서 읽어 앉히고, 표시 이름은 사람이 넣는다.
 *
 * <p>화면에 뜨는 시스템 이름의 정본이 여기다 (→ {@link SystemLabels}).
 * ⛔ 자바 상수나 {@code yml} 로 되돌리지 마라 — 시스템 목록은 사업마다 다르다
 * (2026-08-20 에 채번이 같은 실수를 했고, 세 시스템의 화면이 조용히 번호를 못 받았다).
 */
@Service
public class ProjectSystemService {

    private static final Logger log = LoggerFactory.getLogger(ProjectSystemService.class);

    private final ProjectSystemMapper systems;
    private final PlanningManifestReader manifests;

    public ProjectSystemService(ProjectSystemMapper systems, PlanningManifestReader manifests) {
        this.systems = systems;
        this.manifests = manifests;
    }

    public List<ProjectSystem> all(String projectId) {
        return systems.selectByProjectId(projectId);
    }

    public SystemLabels labels(String projectId) {
        return SystemLabels.of(all(projectId));
    }

    /**
     * 클론의 {@code manifest.json} 에 맞춰 시스템 목록을 바로잡는다 — <b>이름은 건드리지 않는다.</b>
     *
     * <p>⛔ <b>{@code manifest.json} 을 못 읽으면 아무것도 안 한다.</b> 0행으로 밀면 사람이
     * 적어 둔 한글 이름이 「파일 한 번 못 읽음」으로 통째로 날아간다. 채번이 쓰는 방어와 같은 모양이다.
     *
     * <p>⚠ 레포에서 사라진 코드는 지운다 — 목록의 정본이 레포라야 관리 화면이 거짓말을 안 한다.
     * 지워도 다른 표의 {@code system_code} 는 날코드라 걸리는 것이 없다(FK 가 아니다).
     */
    @Transactional
    public void syncFromRepo(String projectId) {
        Set<String> codes = new LinkedHashSet<>();
        manifests.systems(projectId).forEach(system -> codes.add(system.id()));
        if (codes.isEmpty()) {
            log.info("manifest.json 에서 시스템을 못 읽어 시스템 등록을 그대로 둔다 projectId={}", projectId);
            return;
        }
        List<ProjectSystem> current = all(projectId);
        Set<String> known = new LinkedHashSet<>();
        current.forEach(system -> known.add(system.systemCode()));

        codes.stream().filter(code -> !known.contains(code))
                .forEach(code -> systems.insert(ProjectSystem.create(projectId, code, null)));
        current.stream().map(ProjectSystem::systemCode).filter(code -> !codes.contains(code))
                .forEach(code -> systems.deleteByProjectIdAndSystemCode(projectId, code));
    }

    /**
     * 클론·저장소 업데이트가 성공한 뒤에 부른다.
     *
     * <p>⛔ 여기서 터져도 클론은 이미 성공이다 — 그것을 실패로 뒤집지 않으려고 삼킨다.
     * 다음 「저장소 업데이트」가 재시도다(채번과 같은 규칙).
     */
    public void syncQuietly(String projectId) {
        try {
            syncFromRepo(projectId);
        } catch (RuntimeException failure) {
            log.warn("시스템 목록 동기화에 실패했다. 다음 저장소 업데이트가 재시도다 projectId={}",
                    projectId, failure);
        }
    }

    /**
     * 표시 이름만 다시 넣는다.
     *
     * <p>⛔ <b>모르는 코드는 조용히 버린다 — 새 행을 만들지 않는다.</b> 코드의 정본은 레포다.
     * 사람이 손으로 넣은 코드는 어느 화면의 자료와도 만나지 못하는데, 그것이 관리 화면에만
     * 앉아 있으면 「등록했는데 아무 데도 안 나온다」가 된다.
     *
     * <p>⚠ 같은 이름을 두 시스템에 못 준다 — 솔루션 목업의 거르개가 <b>이름</b>으로 거른다.
     * 이름이 겹치면 한 값으로 두 시스템이 걸려 숫자를 못 믿게 된다.
     */
    @Transactional
    public void replaceNames(String projectId, Map<String, String> namesByCode) {
        Map<String, String> wanted = new LinkedHashMap<>();
        namesByCode.forEach((code, name) -> {
            if (code == null || code.isBlank()) return;
            wanted.put(code.strip(), name == null || name.isBlank() ? null : name.strip());
        });

        List<String> named = wanted.values().stream().filter(java.util.Objects::nonNull).toList();
        if (named.stream().distinct().count() != named.size()) {
            throw new IllegalArgumentException("같은 표시 이름을 두 시스템에 쓸 수 없습니다.");
        }

        for (ProjectSystem system : all(projectId)) {
            if (!wanted.containsKey(system.systemCode())) continue;
            String name = wanted.get(system.systemCode());
            if (java.util.Objects.equals(system.displayName(), name)) continue;
            systems.updateDisplayName(projectId, system.systemCode(), name);
        }
    }
}
