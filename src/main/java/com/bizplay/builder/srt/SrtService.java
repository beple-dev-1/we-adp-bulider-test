package com.bizplay.builder.srt;

import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.devrequest.DevelopmentRequest;
import com.bizplay.builder.devrequest.DevelopmentRequestMapper;
import com.bizplay.builder.devrequest.DevelopmentRequestService;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdAnalysisNote;
import com.bizplay.builder.frd.FrdAnalysisNoteMapper;
import com.bizplay.builder.frd.FrdFacet;
import com.bizplay.builder.frd.FrdFacetMapper;
import com.bizplay.builder.frd.FrdItem;
import com.bizplay.builder.frd.FrdItemMapper;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.FlowPost;
import com.bizplay.builder.intake.FlowPostGateway;
import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** SRT 등록부터 개발요청서 생성 전까지의 원문과 연결 정보를 관리한다. */
@Service
public class SrtService {
    private static final String ALL_FACETS = "__ALL__";

    private final SrtMapper srts;
    private final FrdMapper frds;
    private final FrdItemMapper items;
    private final FrdAnalysisNoteMapper notes;
    private final DevelopmentRequestMapper requests;
    private final DevelopmentRequestService developmentRequests;
    private final FlowPostGateway flow;
    private final AccountMapper accounts;
    private final IdSequence ids;
    private final ObjectMapper json;
    private final ProjectFacetMapper projectFacets;
    private final FrdFacetMapper frdFacets;

    @Autowired
    public SrtService(SrtMapper srts, FrdMapper frds, FrdItemMapper items,
                      FrdAnalysisNoteMapper notes, DevelopmentRequestMapper requests,
                      DevelopmentRequestService developmentRequests, FlowPostGateway flow,
                      AccountMapper accounts, IdSequence ids, ObjectMapper json,
                      ProjectFacetMapper projectFacets, FrdFacetMapper frdFacets) {
        this.srts = srts;
        this.frds = frds;
        this.items = items;
        this.notes = notes;
        this.requests = requests;
        this.developmentRequests = developmentRequests;
        this.flow = flow;
        this.accounts = accounts;
        this.ids = ids;
        this.json = json;
        this.projectFacets = projectFacets;
        this.frdFacets = frdFacets;
    }

    /** 기존 단위 테스트와 적용 구분 기능 이전 호출자를 위한 호환 생성자다. */
    public SrtService(SrtMapper srts, FrdMapper frds, FrdItemMapper items,
                      FrdAnalysisNoteMapper notes, DevelopmentRequestMapper requests,
                      DevelopmentRequestService developmentRequests, FlowPostGateway flow,
                      AccountMapper accounts, IdSequence ids, ObjectMapper json) {
        this(srts, frds, items, notes, requests, developmentRequests, flow, accounts, ids, json,
                null, null);
    }

    @Transactional(readOnly = true)
    public List<Row> list(String projectId) {
        List<Srt> all = srts.selectByProjectId(projectId);
        Map<String, String> authors = new LinkedHashMap<>();
        Map<String, List<String>> facetsByFrdId = new LinkedHashMap<>();
        List<String> accountIds = all.stream().map(Srt::ownerAccountId).distinct().toList();
        if (!accountIds.isEmpty()) {
            accounts.selectByIdIn(accountIds).forEach(account -> authors.put(account.getId(), account.getName()));
        }
        if (frdFacets != null) {
            frdFacets.selectByProjectId(projectId).forEach(facet -> facetsByFrdId
                    .computeIfAbsent(facet.frdId(), ignored -> new java.util.ArrayList<>())
                    .add(facet.name()));
        }
        return all.stream().map(srt -> new Row(srt, authors.get(srt.ownerAccountId()),
                srt.devRequestId() == null ? null : requests.selectById(srt.devRequestId()),
                facetsByFrdId.getOrDefault(srt.bridgeFrdId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public Detail read(String projectId, String srtId) {
        Srt srt = require(projectId, srtId);
        String author = accounts.selectById(srt.ownerAccountId()).map(Account::getName).orElse(null);
        DevelopmentRequest request = srt.devRequestId() == null ? null : requests.selectById(srt.devRequestId());
        Source source = sourceOf(srt);
        SrtAiAnalysis analysis = srt.analysisState() == Srt.AnalysisState.COMPLETE
                ? storedAnalysisOf(srt) : null;
        return new Detail(srt, author, request, source.attachments(), analysis);
    }

    @Transactional
    public Srt registerDirect(String projectId, String title, String content, String actorAccountId) {
        return registerDirect(projectId, title, content, actorAccountId, List.of());
    }

    @Transactional
    public Srt registerDirect(String projectId, String title, String content, String actorAccountId,
                              List<String> facetNames) {
        String normalizedTitle = required(title, "제목을 입력해 주세요.", 255);
        String normalizedContent = required(content, "내용을 입력해 주세요.", 20000);
        return register(projectId, Srt.SourceKind.DIRECT, null, normalizedTitle,
                normalizedContent, null, actorAccountId, facetNames);
    }

    @Transactional
    public Srt registerFlow(String projectId, String flowTaskNumber, String actorAccountId) {
        return registerFlow(projectId, flowTaskNumber, actorAccountId, List.of());
    }

    @Transactional
    public Srt registerFlow(String projectId, String flowTaskNumber, String actorAccountId,
                            List<String> facetNames) {
        String taskNumber = required(flowTaskNumber, "플로우 업무번호를 입력해 주세요.", 30);
        if (!taskNumber.matches("^[0-9]+$")) {
            throw new IllegalArgumentException("플로우 업무번호는 숫자로 입력해 주세요.");
        }
        FlowPost post = flow.getByTaskNumber(taskNumber);
        String title = required(post.title(), "플로우 원문에 제목이 없습니다.", 255);
        String content = required(post.content(), "플로우 원문에 내용이 없습니다.", 20000);
        FlowPost source = new FlowPost(post.postId(), post.title(), post.content(), post.connectUrl(),
                post.projectTitle(), post.attachments(), List.of());
        return register(projectId, Srt.SourceKind.FLOW, taskNumber, title, content, write(source),
                actorAccountId, facetNames);
    }

    private Srt register(String projectId, Srt.SourceKind sourceKind, String flowTaskNumber,
                         String title, String content, String sourceJson, String actorAccountId,
                         List<String> facetNames) {
        List<String> chosenFacets = chosenFacets(projectId, facetNames);
        String srtId = ids.next(IdSequence.Kind.SRT);
        Srt srt = new Srt(srtId, projectId, srts.allocateNumber(projectId), sourceKind,
                flowTaskNumber, title, content, sourceJson, actorAccountId, null, null,
                Srt.AnalysisState.READY, null, null, null);
        srts.insert(srt);

        String frdId = ids.next(IdSequence.Kind.FRD);
        int frdNumber = frds.allocateNumber(projectId);
        Frd bridge = new Frd(frdId, projectId, frdNumber, title, null, Frd.SourceKind.SRT,
                srt.label(), content, sourceKind == Srt.SourceKind.FLOW ? Instant.now() : null,
                "SRT는 화면 선택 없이 개발요청서로 전환합니다.", Frd.State.SCOPE_REVIEW,
                null, actorAccountId, null, null, null);
        frds.insert(bridge);
        chosenFacets.forEach(name -> frdFacets.insert(FrdFacet.create(frdId, projectId, name)));

        writeSourceRequirement(frdId, content);
        if (srts.connectBridge(srtId, frdId) != 1) {
            throw new IllegalStateException("SRT 원문을 저장하지 못했습니다.");
        }
        return srts.selectById(srtId);
    }

    /** 개발요청서 생성 전인 직접 입력 SRT의 제목과 내용을 수정한다. */
    @Transactional
    public Srt update(String projectId, String srtId, String title, String content) {
        Srt current = editable(projectId, srtId);
        if (current.sourceKind() == Srt.SourceKind.FLOW) {
            throw new IllegalStateException("플로우로 등록한 SRT는 수정할 수 없습니다.");
        }
        String updatedTitle = required(title, "제목을 입력해 주세요.", 255);
        String updatedContent = required(content, "내용을 입력해 주세요.", 20000);
        int changed = srts.updateDirect(srtId, updatedTitle, updatedContent);
        if (changed != 1 || frds.updateSrtSource(current.bridgeFrdId(), updatedTitle, updatedContent) != 1) {
            throw new IllegalStateException("개발요청서 생성 전인 SRT만 수정할 수 있습니다.");
        }
        items.deleteByFrdId(current.bridgeFrdId());
        notes.deleteByFrdId(current.bridgeFrdId());
        writeSourceRequirement(current.bridgeFrdId(), updatedContent);
        srts.updateAnalysisState(srtId, Srt.AnalysisState.READY, null);
        return srts.selectById(srtId);
    }

    /** FRD 등록과 같은 규칙으로 전체 선택을 펼치고 프로젝트에 없는 적용 구분을 거절한다. */
    private List<String> chosenFacets(String projectId, List<String> facetNames) {
        if (projectFacets == null || frdFacets == null) return List.of();
        List<ProjectFacet> available = projectFacets.selectByProjectId(projectId);
        Set<String> allowed = available.stream().map(ProjectFacet::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> requested = facetNames == null ? List.of() : facetNames.stream()
                .filter(Objects::nonNull).map(String::strip).filter(name -> !name.isEmpty())
                .distinct().toList();
        List<String> chosen = !available.isEmpty()
                && (requested.isEmpty() || requested.contains(ALL_FACETS))
                ? List.copyOf(allowed) : requested;
        if (!allowed.containsAll(chosen)) {
            throw new IllegalArgumentException("프로젝트에 등록되지 않은 적용 구분이 포함되어 있습니다.");
        }
        return chosen;
    }

    /** 등록·수정된 원문을 AI가 읽기 시작했음을 저장한다. */
    @Transactional
    public Srt beginAnalysis(String projectId, String srtId) {
        Srt srt = analysisTarget(projectId, srtId);
        if (srt.analysisState() == Srt.AnalysisState.COMPLETE) return srt;
        if (srts.updateAnalysisState(srtId, Srt.AnalysisState.ANALYZING, null) != 1) {
            throw new IllegalStateException("SRT 분석 상태를 저장하지 못했습니다.");
        }
        return srts.selectById(srtId);
    }

    /** AI의 최소 적합성 분석 결과를 내부 FRD 연결에 보존한다. */
    @Transactional
    public void completeAnalysis(String projectId, String srtId, SrtAiAnalysis analysis) {
        Srt srt = analysisTarget(projectId, srtId);
        if (analysis == null || !analysis.eligible()
                || analysis.analysisComment() == null || analysis.analysisComment().isBlank()
                || analysis.requirements().isEmpty() || analysis.acceptanceCriteria().isEmpty()) {
            throw new IllegalStateException("유효한 AI 분석 결과를 확인하지 못했습니다.");
        }
        writeAnalysis(srt.bridgeFrdId(), analysis);
        if (srts.updateAnalysisState(srtId, Srt.AnalysisState.COMPLETE, analysis.analysisComment()) != 1) {
            throw new IllegalStateException("SRT 분석 결과를 저장하지 못했습니다.");
        }
    }

    @Transactional
    public void finishAnalysis(String projectId, String srtId, Srt.AnalysisState state, String message) {
        analysisTarget(projectId, srtId);
        if (state != Srt.AnalysisState.REJECTED && state != Srt.AnalysisState.FAILED) {
            throw new IllegalArgumentException("종료할 수 없는 SRT 분석 상태입니다.");
        }
        if (srts.updateAnalysisState(srtId, state, message) != 1) {
            throw new IllegalStateException("SRT 분석 상태를 저장하지 못했습니다.");
        }
    }

    /** 등록 단계에서 저장한 분석 결과를 개발요청서 생성에 사용한다. */
    @Transactional(readOnly = true)
    public SrtAiAnalysis storedAnalysis(String projectId, String srtId) {
        Srt srt = analysisTarget(projectId, srtId);
        if (srt.analysisState() != Srt.AnalysisState.COMPLETE) {
            throw new IllegalStateException("SRT 분석이 끝난 뒤 개발요청서를 생성할 수 있습니다.");
        }
        return storedAnalysisOf(srt);
    }

    private SrtAiAnalysis storedAnalysisOf(Srt srt) {
        List<String> requirements = items.selectByFrdId(srt.bridgeFrdId()).stream()
                .map(FrdItem::requirement).toList();
        List<String> criteria = notes.selectByFrdId(srt.bridgeFrdId()).stream()
                .filter(note -> note.kind() == FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION)
                .map(FrdAnalysisNote::content).toList();
        if (requirements.isEmpty() || criteria.isEmpty()) {
            throw new IllegalStateException("저장된 SRT 분석 결과를 확인하지 못했습니다.");
        }
        String comment = srt.analysisMessage();
        if (comment == null || comment.isBlank()) {
            comment = "AI가 개발 변경 요청으로 확인하고 요구사항 %d건과 완료 조건 %d건으로 정리했습니다."
                    .formatted(requirements.size(), criteria.size());
        }
        return new SrtAiAnalysis(true, null, comment, requirements, criteria);
    }

    /** 개발요청서가 아직 없는 SRT와 내부 호환 행을 함께 삭제한다. */
    @Transactional
    public void delete(String projectId, String srtId) {
        Srt current = editable(projectId, srtId);
        if (srts.deleteUnprepared(projectId, srtId) != 1
                || frds.deleteIncomplete(projectId, current.bridgeFrdId()) != 1) {
            throw new IllegalStateException("개발요청서 생성 전인 SRT만 삭제할 수 있습니다.");
        }
    }

    /** 상세에서 생성을 선택한 시점에만 개발요청서를 만들고 SRT와 연결한다. */
    @Transactional
    public Srt prepareDevelopmentRequest(String projectId, String srtId,
                                         String analyzedTitle, String analyzedContent,
                                         SrtAiAnalysis analysis) {
        Srt srt = srts.selectByIdForUpdate(srtId);
        if (srt == null || !srt.projectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 SRT가 없습니다: " + srtId);
        }
        if (srt.devRequestId() != null) return srt;
        if (srt.bridgeFrdId() == null) {
            throw new IllegalStateException("SRT 정리가 끝난 뒤 전송할 수 있습니다.");
        }
        Frd bridge = frds.selectById(srt.bridgeFrdId());
        if (bridge == null || bridge.sourceKind() != Frd.SourceKind.SRT) {
            throw new IllegalStateException("SRT 출처를 확인하지 못했습니다.");
        }
        if (!srt.title().equals(analyzedTitle) || !srt.content().equals(analyzedContent)) {
            throw new IllegalStateException("AI 분석 중 SRT 내용이 변경됐습니다. 다시 생성해 주세요.");
        }
        if (analysis == null || !analysis.eligible()
                || analysis.requirements().isEmpty() || analysis.acceptanceCriteria().isEmpty()) {
            throw new IllegalStateException("유효한 AI 분석 결과가 있어야 개발요청서를 생성할 수 있습니다.");
        }
        writeAnalysis(srt.bridgeFrdId(), analysis);
        DevelopmentRequest request = developmentRequests.createFromConfirmedScope(
                projectId, srt.bridgeFrdId(), analysis.analysisComment());
        if (srts.connectRequest(srtId, request.id()) != 1) {
            throw new IllegalStateException("SRT와 개발요청서를 연결하지 못했습니다.");
        }
        return srts.selectById(srtId);
    }

    /** AI가 읽을 현재 SRT와 내부 출처 연결을 확인한다. */
    @Transactional(readOnly = true)
    public Srt analysisTarget(String projectId, String srtId) {
        Srt srt = require(projectId, srtId);
        if (srt.devRequestId() != null) return srt;
        Frd bridge = srt.bridgeFrdId() == null ? null : frds.selectById(srt.bridgeFrdId());
        if (bridge == null || bridge.sourceKind() != Frd.SourceKind.SRT) {
            throw new IllegalStateException("SRT 출처를 확인하지 못했습니다.");
        }
        return srt;
    }

    private Srt require(String projectId, String srtId) {
        Srt srt = srts.selectById(srtId);
        if (srt == null || !srt.projectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 SRT가 없습니다: " + srtId);
        }
        return srt;
    }

    private Srt editable(String projectId, String srtId) {
        Srt srt = srts.selectByIdForUpdate(srtId);
        if (srt == null || !srt.projectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 SRT가 없습니다: " + srtId);
        }
        if (srt.devRequestId() != null || srt.bridgeFrdId() == null) {
            throw new IllegalStateException("개발요청서 생성 전인 SRT만 수정하거나 삭제할 수 있습니다.");
        }
        return srt;
    }

    /** AI 처리 전에는 사용자가 등록한 원문 한 건만 개발요청서의 입력 재료로 보존한다. */
    private void writeSourceRequirement(String frdId, String content) {
        items.insert(FrdItem.of(ids.next(IdSequence.Kind.FRD_ITEM), frdId, 1,
                content, FrdItem.Nature.DEVELOP, FrdItem.Verdict.NO_SCREEN,
                List.of(), null));
    }

    /** 원문 한 건을 AI가 정리한 요구사항과 완료 조건으로 교체한다. */
    private void writeAnalysis(String frdId, SrtAiAnalysis analysis) {
        items.deleteByFrdId(frdId);
        int seq = 0;
        for (String requirement : analysis.requirements()) {
            items.insert(FrdItem.of(ids.next(IdSequence.Kind.FRD_ITEM), frdId, ++seq,
                    requirement, FrdItem.Nature.DEVELOP, FrdItem.Verdict.NO_SCREEN,
                    List.of(), null));
        }
        notes.deleteByFrdId(frdId);
        seq = 0;
        for (String criterion : analysis.acceptanceCriteria()) {
            notes.insert(new FrdAnalysisNote(
                    ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE), frdId, ++seq,
                    FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION,
                    criterion, null));
        }
    }

    private Source sourceOf(Srt srt) {
        if (srt.sourceKind() != Srt.SourceKind.FLOW || srt.sourceJson() == null) return Source.empty();
        try {
            FlowPost post = json.readValue(srt.sourceJson(), FlowPost.class);
            List<SourceAttachment> attachments = post.attachments().stream()
                    .map(file -> new SourceAttachment(file.fileName(), file.url(), file.size())).toList();
            return new Source(attachments);
        } catch (Exception ignored) {
            return Source.empty();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failed) {
            throw new IllegalStateException("플로우 원문을 저장할 수 없습니다.", failed);
        }
    }

    private static String required(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        String normalized = value.strip();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("입력 내용이 너무 깁니다.");
        return normalized;
    }

    public record Row(Srt srt, String authorName, DevelopmentRequest request,
                      List<String> facetNames) {
        public Row {
            facetNames = facetNames == null ? List.of() : List.copyOf(facetNames);
        }

        public Row(Srt srt, String authorName, DevelopmentRequest request) {
            this(srt, authorName, request, List.of());
        }

        public String stateLabel() {
            return srt.stateLabel();
        }

        public String stateClass() {
            if (srt.devRequestId() != null) return "status-badge--complete";
            return switch (srt.analysisState()) {
                case READY, ANALYZING -> "status-badge--progress";
                case COMPLETE -> "status-badge--waiting";
                case REJECTED, FAILED -> "status-badge--error";
            };
        }
    }
    public record Detail(Srt srt, String authorName, DevelopmentRequest request,
                         List<SourceAttachment> attachments, SrtAiAnalysis analysis) {
        public boolean canSend() {
            return request == null || request.deliveryState() != DevelopmentRequest.DeliveryState.SENT;
        }
    }
    private record Source(List<SourceAttachment> attachments) {
        private static Source empty() { return new Source(List.of()); }
    }
}
