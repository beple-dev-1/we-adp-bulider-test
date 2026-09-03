package com.bizplay.builder.project;

import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.RepoProbe;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.IntakeFacet;
import com.bizplay.builder.intake.IntakeFacetMapper;
import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.secret.Sealed;
import com.bizplay.builder.secret.SecretSealer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private static final DateTimeFormatter CREATED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** {@link #register} 의 편의 갈래가 쓰는 기본값 — 화면에서 플랫폼 코드를 안 받는 옛 호출자용. */
    private static final String DEFAULT_PLATFORM_CODE = "PS";

    private final ProjectMapper projects;
    private final RepoProbe probe;
    private final SecretSealer sealer;
    private final GitCommand git;
    private final IdSequence ids;
    private final ProjectFacetMapper projectFacets;
    private final IntakeFacetMapper intakeFacets;
    private final RepositoryUpdateMapper repositoryUpdates;
    private final ProjectSystemMapper projectSystems;

    public ProjectService(ProjectMapper projects, RepoProbe probe, SecretSealer sealer,
                          GitCommand git, IdSequence ids, ProjectFacetMapper projectFacets,
                          IntakeFacetMapper intakeFacets, RepositoryUpdateMapper repositoryUpdates,
                          ProjectSystemMapper projectSystems) {
        this.projects = projects;
        this.probe = probe;
        this.sealer = sealer;
        this.git = git;
        this.ids = ids;
        this.projectFacets = projectFacets;
        this.intakeFacets = intakeFacets;
        this.repositoryUpdates = repositoryUpdates;
        this.projectSystems = projectSystems;
    }

    /**
     * 적용 구분이 없는 프로젝트. ⚠ 0개가 정상이다 — 「공통」이라는 값을 따로 만들지 않는다.
     *
     * <p>⚠ <b>여기도 {@code @Transactional} 을 단다.</b> 안에서 부르는 5-인자 갈래의 것은
     * <b>자기 호출이라 프록시를 안 타서 안 걸린다</b> — 그러면 프로젝트 줄과 적용 구분 줄이
     * <b>따로 커밋</b>돼, 적용 구분을 넣다 죽으면 프로젝트만 앉은 반쪽이 남는다.
     * 바깥에서 먼저 걸어 둬야 안쪽 자기 호출도 그 트랜잭션을 그냥 쓴다.
     *
     * <p>(2026-08-15 까지는 여기 적힌 까닭이 「{@code em.refresh} 가 트랜잭션을 요구한다」였다.
     * MyBatis 로 옮기며 그 호출이 사라졌지만 <b>어노테이션은 그대로 남긴다</b> — 위의 까닭이 있다.)
     */
    @Transactional
    public Project register(String name, String repoUrl, String defaultBranch, String token) {
        return register(name, repoUrl, defaultBranch, token, List.of());
    }

    @Transactional
    public Project register(String name, String repoUrl, String defaultBranch, String token,
                            List<String> facets) {
        return registerConfigured(name, repoUrl, defaultBranch, token, DEFAULT_PLATFORM_CODE,
                normalizeFacets(facets).stream().map(nameValue -> new FacetSetting(nameValue, nameValue)).toList());
    }

    /**
     * 적용 구분 코드와 표시 이름을 분리해 프로젝트를 등록한다.
     *
     * <p>⚠ <b>{@code platformCode} 는 등록 시점에 딱 한 번 정해진다.</b> 클론이 앉으면 바로
     * 표준 화면ID 채번이 도는데, 이미 박힌 ID 는 안 바꾸는 규칙이라 나중에 고쳐도 소용없다
     * (정본: {@code docs/superpowers/specs/2026-08-20-screen-standard-id-design.md} §2).
     */
    @Transactional
    public Project registerConfigured(String name, String repoUrl, String defaultBranch, String token,
                                      String platformCode, List<FacetSetting> facetSettings) {
        // ⛔ 이름을 다듬고, 빈 것을 여기서 막는다. 화면의 required 는 **빈 칸만 든 이름을 통과시킨다.**
        //    그것이 저장되면 나중에 산출물·작업대 화면이 열릴 때마다 500 이 난다 —
        //    껍데기 계약(ShellContract)이 프로젝트 안 화면에 빈 이름을 금지하기 때문이다.
        //    막는 자리는 화면이 아니라 여기다: 저장된 뒤에 고치는 것이 제일 비싸다.
        //    (2026-08-09 코덱스 적대검증 2회차가 짚었다 — 계약을 조이면서 이 자리를 안 맞췄다.)
        String trimmedName = name == null ? "" : name.strip();
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("프로젝트 이름이 비어 있다");
        }
        // ⚠ 등록 화면의 pattern="[A-Z0-9]{2,4}" 는 사람에게 주는 힌트일 뿐이다 — 여기서 다시 잰다.
        //    숫자도 받는다(예: PS2) — 다음 사업이 알파벳 두 글자로 안 모자를 수 있다.
        String trimmedPlatform = platformCode == null ? "" : platformCode.strip();
        if (!trimmedPlatform.matches("^[A-Z0-9]{2,4}$")) {
            throw new IllegalArgumentException("플랫폼 코드는 대문자·숫자 2~4자로 입력해 주세요: " + trimmedPlatform);
        }
        if (projects.selectByName(trimmedName).isPresent()) {
            throw new IllegalArgumentException("같은 이름의 프로젝트가 이미 있다: " + trimmedName);
        }
        RepoProbe.ProbeResult result = probe.probe(repoUrl, defaultBranch, token);
        if (!result.ok()) {
            // 확인에 실패하면 등록이 저장되지 않는다 — project-setup 이 정한 그대로다.
            throw new IllegalArgumentException(result.reason());
        }
        Sealed sealed = sealer.seal(token);
        // ⚠ 채번을 확인(probe) 뒤에 둔다. 확인에 실패하면 등록이 저장되지 않는데,
        //    앞에 두면 그때마다 번호가 하나씩 사라진다(시퀀스는 롤백해도 안 돌아온다).
        String projectId = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(projectId, trimmedName, repoUrl, defaultBranch, trimmedPlatform,
                sealed.cipher(), sealed.nonce()));

        // ⚠ created_at 은 DB 기본값(now())이 채운다 — 방금 넣은 자바 쪽 객체는 그 값을 모른다.
        //    그래서 한 번 되읽어 돌려준다. 안 그러면 같은 트랜잭션 안에서 상세를 곧바로 열 때
        //    (테스트가 그렇게 한다) 등록일시가 null 이라 서식을 못 정하고 던진다.
        // ⛔ <selectKey> 로 값을 꽂으려 하지 마라 — Project 는 불변이라 꽂을 자리가 없다.
        // ⚠ 2026-08-15 까지 여기 있던 repository.flush() + em.refresh() 는 지웠다. 그것은 JPA 가
        //    쓰기를 커밋 직전까지 미뤄서(write-behind) ① 행이 아직 없어 되읽을 수 없고
        //    ② 바로 아래 적용 구분 MyBatis INSERT 가 FK 를 못 채우던 두 문제를 한꺼번에 막던 것이다.
        //    프로젝트 INSERT 도 MyBatis 가 되어 곧장 들어가므로 두 까닭이 다 사라졌다.
        Project saved = projects.selectById(projectId).orElseThrow();

        // 다듬고 · 빈 것을 버리고 · 중복을 없앤다. 넣은 순서는 안 지킨다 — 화면이 이름순으로 읽는다.
        normalizeSettings(facetSettings).forEach(f ->
                projectFacets.insert(ProjectFacet.create(projectId, f.code(), f.name())));

        return saved;
    }

    /** 다듬고 · 빈 것을 버리고 · 중복을 없앤다. {@link #register} 와 {@link #replaceFacets} 가 같이 쓴다. */
    private static List<String> normalizeFacets(List<String> facets) {
        return facets.stream()
                .map(f -> f == null ? "" : f.strip())
                .filter(f -> !f.isEmpty())
                .distinct()
                .toList();
    }

    public record FacetSetting(String code, String name) {
    }

    /** 빈 행은 버리고, 코드와 표시 이름은 둘 다 있어야 하며 프로젝트 안에서 각각 유일해야 한다. */
    private static List<FacetSetting> normalizeSettings(List<FacetSetting> settings) {
        List<FacetSetting> normalized = settings.stream()
                .map(setting -> new FacetSetting(
                        setting.code() == null ? "" : setting.code().strip(),
                        setting.name() == null ? "" : setting.name().strip()))
                .filter(setting -> !setting.code().isEmpty() || !setting.name().isEmpty())
                .toList();
        for (FacetSetting setting : normalized) {
            if (setting.code().isEmpty() || setting.name().isEmpty()) {
                throw new IllegalArgumentException("적용 구분 코드와 표시 이름을 모두 입력해 주세요.");
            }
            if (!setting.code().matches("[\\p{L}\\p{N}][\\p{L}\\p{N}-]*")) {
                throw new IllegalArgumentException("적용 구분 코드는 문자·숫자·하이픈으로 입력해 주세요: " + setting.code());
            }
        }
        if (normalized.stream().map(FacetSetting::code).distinct().count() != normalized.size()) {
            throw new IllegalArgumentException("같은 적용 구분 코드를 두 번 사용할 수 없습니다.");
        }
        if (normalized.stream().map(FacetSetting::name).distinct().count() != normalized.size()) {
            throw new IllegalArgumentException("같은 적용 구분 표시 이름을 두 번 사용할 수 없습니다.");
        }
        return normalized;
    }

    @Transactional
    public void replaceToken(String projectId, String token) {
        Project project = projects.selectById(projectId).orElseThrow();
        RepoProbe.ProbeResult result =
                probe.probe(project.getRepoUrl(), project.getDefaultBranch(), token);
        if (!result.ok()) {
            throw new IllegalArgumentException(result.reason());
        }
        Sealed sealed = sealer.seal(token);
        // ⛔ 읽어 온 project 를 고쳐 두고 끝내지 마라. JPA 때는 더티 체킹이 저장해 줬지만
        //    MyBatis 에는 그것이 없다 — 고치는 길은 이 update 하나다.
        if (projects.updateToken(projectId, sealed.cipher(), sealed.nonce()) == 0) {
            throw new IllegalStateException("그런 프로젝트가 없다: " + projectId);
        }
    }

    @Transactional(readOnly = true)
    public String tokenOf(String projectId) {
        Project project = projects.selectById(projectId).orElseThrow();
        return sealer.unseal(new Sealed(project.getSealedToken(), project.getTokenNonce()));
    }

    /** 클론에 필요한 것을 한 번에 꺼낸다. 토큰 풀기가 여기서 끝나므로 뒤는 DB 를 안 만진다. */
    public record CloneMaterials(String defaultBranch, String authenticatedUrl) {}

    @Transactional(readOnly = true)
    public CloneMaterials cloneMaterials(String projectId) {
        Project p = projects.selectById(projectId).orElseThrow();
        return new CloneMaterials(p.getDefaultBranch(),
                git.authenticatedUrl(p.getRepoUrl(), tokenOf(projectId)));
    }

    @Transactional
    public void cloneSucceeded(String projectId) {
        markReady(projectId);
    }

    @Transactional
    public void cloneFailed(String projectId, String reason) {
        markFailed(projectId, reason);
    }

    /** 다시 받기. ⚠ 실패 이유를 같이 비운다 — 받는 중인 프로젝트에 지난 이유가 남으면 화면이 거짓말을 한다. */
    @Transactional
    public void retry(String projectId) {
        changeState(projectId, ProjectState.RECEIVING, null);
    }

    @Transactional(readOnly = true)
    public List<Project> all() {
        return projects.selectAll();
    }

    @Transactional(readOnly = true)
    public List<Project> ready() {
        return projects.selectByState(ProjectState.READY).stream()
                .sorted(Comparator.comparing(Project::getId))
                .toList();
    }

    /**
     * 기획자에게 열어 줄 수 있는 프로젝트만 찾는다.
     *
     * <p>⚠ {@code READY} 가 아니면 없는 것으로 친다 — {@code RECEIVING} 은 클론이 도는 중이라
     * 워크트리가 아직 없고, {@code FAILED} 는 받다 만 것이다. 둘 다 열면 화면은 뜨는데
     * <b>그 안에 아무것도 없다.</b> 상태를 갈라 다른 말을 해 줄 자리는 관리 화면이지 여기가 아니다.
     */
    @Transactional(readOnly = true)
    public Optional<Project> findReady(String projectId) {
        return projects.selectById(projectId)
                .filter(p -> p.getState() == ProjectState.READY);
    }

    @Transactional(readOnly = true)
    public ProjectDetailView detail(String projectId) {
        Project project = projects.selectById(projectId).orElseThrow();
        // ⚠ 2026-08-15 까지 여기엔 「등록일시가 null 이면 '없음'」이라는 갈래가 있었다. 그것은 JPA 가
        //    쓰기를 미뤄(write-behind) 방금 저장한 자바 객체의 created_at 이 null 로 남던 자리를
        //    막던 것이다. 이제는 늘 DB 에서 되읽고 그 열은 not null default now() 라 null 이 없다.
        RepositoryUpdate update = repositoryUpdates.selectByProjectId(projectId).orElse(null);
        return new ProjectDetailView(project,
                projectFacets.selectByProjectId(projectId),
                // ⚠ 시스템은 등록 화면에서 못 받는다 — 그때는 클론이 없어 목록을 모른다.
                //   클론·저장소 업데이트가 성공한 뒤 manifest.json 에서 앉는다.
                projectSystems.selectByProjectId(projectId),
                CREATED_AT.format(project.getCreatedAt()),
                update,
                update == null || update.finishedAt() == null ? null : CREATED_AT.format(update.finishedAt()));
    }

    /**
     * 적용 구분을 다시 넣는다. ⚠ <b>통째로 지웠다 다시 넣지 않는다</b> — 지운 이름이
     * {@code adk_builder_intake_facet} 에서 여전히 쓰이고 있으면 FK 위반으로 500 이 난다
     * (받은 문서가 하나라도 있는 프로젝트는 늘 이 자리에 걸린다). 그래서 <b>차이만</b> 만진다 —
     * 빠지는 이름만 지우고, 새로 붙는 이름만 넣고, 그대로인 이름은 손대지 않는다.
     *
     * <p>빠지는 이름 중 접수가 하나라도 쓰고 있으면 <b>편집 자체를 거절한다.</b> 그 이름만 조용히
     * 안 지우면 화면과 실제로 저장된 값이 어긋나고, 지우면 FK 가 500 으로 던진다 — 둘 다 나쁘다.
     * 거절하며 어느 적용 구분이 걸려 있는지 사람이 읽는 말로 알려 준다.
     */
    @Transactional
    public void replaceFacets(String projectId, List<String> facets) {
        replaceFacetSettings(projectId,
                normalizeFacets(facets).stream().map(name -> new FacetSetting(name, name)).toList());
    }

    @Transactional
    public void replaceFacetSettings(String projectId, List<FacetSetting> settings) {
        List<FacetSetting> desired = normalizeSettings(settings);
        List<ProjectFacet> current = projectFacets.selectByProjectId(projectId);
        Map<String, ProjectFacet> currentByCode = current.stream()
                .collect(Collectors.toMap(ProjectFacet::code, Function.identity()));
        Map<String, ProjectFacet> currentByName = current.stream()
                .collect(Collectors.toMap(ProjectFacet::name, Function.identity()));

        Set<ProjectFacet> matched = new LinkedHashSet<>();
        for (FacetSetting wanted : desired) {
            ProjectFacet sameCode = currentByCode.get(wanted.code());
            ProjectFacet sameName = currentByName.get(wanted.name());
            ProjectFacet found = sameCode != null ? sameCode : sameName;
            if (found != null) {
                matched.add(found);
            }
        }

        List<String> toRemove = current.stream()
                .filter(facet -> !matched.contains(facet))
                .map(ProjectFacet::name)
                .toList();

        if (!toRemove.isEmpty()) {
            List<String> inUse = intakeFacets.selectByProjectIdAndNameIn(projectId, toRemove).stream()
                    .map(IntakeFacet::name)
                    .distinct()
                    .sorted()
                    .toList();
            if (!inUse.isEmpty()) {
                throw new IllegalArgumentException(
                        "받은 문서가 걸려 있어 지울 수 없는 적용 구분: " + String.join(", ", inUse));
            }
        }

        toRemove.forEach(name -> projectFacets.deleteByProjectIdAndName(projectId, name));

        for (FacetSetting wanted : desired) {
            ProjectFacet sameCode = currentByCode.get(wanted.code());
            ProjectFacet sameName = currentByName.get(wanted.name());
            if (sameCode != null) {
                if (!sameCode.name().equals(wanted.name())) {
                    ProjectFacet occupied = currentByName.get(wanted.name());
                    if (occupied != null && !occupied.code().equals(wanted.code())) {
                        throw new IllegalArgumentException("다른 적용 구분이 사용 중인 표시 이름입니다: " + wanted.name());
                    }
                    projectFacets.updateName(projectId, wanted.code(), wanted.name());
                }
            } else if (sameName != null) {
                projectFacets.updateCode(projectId, wanted.name(), wanted.code());
            } else {
                projectFacets.insert(ProjectFacet.create(projectId, wanted.code(), wanted.name()));
            }
        }
    }

    /** 프로젝트는 계속 열어 둔 채 저장소 업데이트 시도만 원자적으로 잡는다. */
    @Transactional
    public boolean requestRepositoryUpdate(String projectId) {
        Project project = projects.selectById(projectId).orElseThrow();
        if (project.getState() != ProjectState.READY) {
            throw new IllegalArgumentException("준비된 프로젝트만 저장소를 업데이트할 수 있습니다.");
        }
        return repositoryUpdates.tryStart(projectId) == 1;
    }

    @Transactional
    public void repositoryUpdateSucceeded(String projectId, String fromCommit,
                                          String currentCommit, boolean changed) {
        if (repositoryUpdates.updateSucceeded(projectId, fromCommit, currentCommit, changed) == 0) {
            throw new IllegalStateException("실행 중인 저장소 업데이트가 없습니다: " + projectId);
        }
    }

    @Transactional
    public void repositoryUpdateFailed(String projectId, String reason) {
        repositoryUpdates.updateFailed(projectId, reason);
    }

    /**
     * 테스트와 클론 일꾼이 같은 문을 쓴다.
     * ⚠ 실패 이유를 같이 비운다 — 준비된 프로젝트에 지난 이유가 남으면 상세가 「실패했다」고 말한다.
     */
    @Transactional
    public void markReady(String projectId) {
        changeState(projectId, ProjectState.READY, null);
    }

    @Transactional
    public void markFailed(String projectId, String reason) {
        changeState(projectId, ProjectState.FAILED, reason);
    }

    /**
     * 상태를 바꾸는 <b>한 자리</b>다.
     *
     * <p>⛔ 여기를 거치지 않고 {@link Project} 를 고치는 메서드를 만들지 마라 — JPA 의 더티 체킹은
     * 2026-08-15 에 사라졌다. 엔티티를 고치면 <b>DB 는 안 바뀌는데 예외도 안 난다.</b>
     *
     * <p>⚠ 바뀐 줄이 0 이면 그런 프로젝트가 없다는 뜻이라 던진다 — JPA 때 {@code findById(...)
     * .orElseThrow()} 가 하던 몫이다. 조용히 넘기면 클론이 끝났는데 아무 데도 안 적히고,
     * 화면은 영원히 「받는 중」으로 남는다.
     */
    private void changeState(String projectId, ProjectState state, String failureReason) {
        if (projects.updateState(projectId, state, failureReason) == 0) {
            throw new IllegalStateException("그런 프로젝트가 없다: " + projectId);
        }
    }
}
