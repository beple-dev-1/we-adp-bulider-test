package com.bizplay.builder.businesslanguage;

import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/projects/{projectId}/artifacts/business-language")
public class BusinessLanguageController {

    private static final String ARTIFACT_KEY = "business-language";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    private final BusinessDocumentService documents;
    private final BusinessDocumentSeedService seeds;
    private final AccountMapper accounts;

    public BusinessLanguageController(BusinessDocumentService documents, BusinessDocumentSeedService seeds,
                                      AccountMapper accounts) {
        this.documents = documents;
        this.seeds = seeds;
        this.accounts = accounts;
    }

    @GetMapping
    public String document(@PathVariable String projectId,
                           @RequestParam(defaultValue = "policy") String tab,
                           @RequestParam(defaultValue = "false") boolean edit,
                           @RequestParam(required = false) Integer editTerm,
                           @RequestParam(defaultValue = "false") boolean newTerm,
                           Model model) {
        String selected = "terms".equals(tab) ? "terms" : "policy";
        var policy = documents.find(projectId, BusinessDocumentKind.POLICY);
        var terms = documents.find(projectId, BusinessDocumentKind.STANDARD_TERMS);
        model.addAttribute("title", "정책·표준용어");
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", ARTIFACT_KEY);
        model.addAttribute("projectId", projectId);
        model.addAttribute("tab", selected);
        model.addAttribute("editing", edit && "policy".equals(selected));
        model.addAttribute("editTerm", editTerm);
        model.addAttribute("addingTerm", newTerm && "terms".equals(selected));
        model.addAttribute("policy", policy.orElse(null));
        model.addAttribute("termsDocument", terms.orElse(null));
        model.addAttribute("ready", policy.isPresent() && terms.isPresent());
        BusinessDocumentSeed seed = seedsState(projectId);
        model.addAttribute("seed", seed);
        model.addAttribute("seedFailureMessage", seedFailureMessage(seed == null ? null : seed.failedReason()));
        model.addAttribute("domainsReady", documents.hasDomainDocuments(projectId));
        if (policy.isPresent()) {
            model.addAttribute("policyHtml", documents.policyHtml(policy.get()));
            model.addAttribute("policyHeadings", documents.policyHeadings(policy.get()));
            model.addAttribute("policyUpdatedBy", updatedByName(policy.get()));
        }
        if (terms.isPresent()) {
            model.addAttribute("terms", termViews(projectId, terms.get()));
            model.addAttribute("termsUpdatedBy", updatedByName(terms.get()));
        }
        return "artifacts/business-language";
    }

    @GetMapping("/policy/download")
    public ResponseEntity<byte[]> downloadPolicy(@PathVariable String projectId) {
        BusinessDocument policy = documents.find(projectId, BusinessDocumentKind.POLICY)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "정책서를 찾을 수 없습니다."));
        String revisionDate = DateTimeFormatter.ISO_LOCAL_DATE.format(policy.updatedAt().atZone(BUSINESS_ZONE));
        String fileName = "사업-정책서_%s.md".formatted(revisionDate);
        String disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build().toString();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(policy.content().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/history")
    public String history(@PathVariable String projectId,
                          @RequestParam(defaultValue = "policy") String tab,
                          @RequestParam(required = false) Integer revision,
                          Model model) {
        String selectedTab = "terms".equals(tab) ? "terms" : "policy";
        BusinessDocumentKind kind = "terms".equals(selectedTab)
                ? BusinessDocumentKind.STANDARD_TERMS : BusinessDocumentKind.POLICY;
        List<BusinessDocumentRevision> history = documents.revisions(projectId, kind);
        if (history.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "수정이력을 찾을 수 없습니다.");
        BusinessDocumentRevision selected = revision == null
                ? history.get(0)
                : history.stream().filter(item -> item.revisionNo() == revision).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "선택한 수정이력을 찾을 수 없습니다."));
        BusinessDocumentRevision previous = history.stream()
                .filter(item -> item.revisionNo() < selected.revisionNo()).findFirst().orElse(null);
        Map<String, String> editorNames = editorNames(history);

        model.addAttribute("title", "정책·표준용어 수정이력");
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", ARTIFACT_KEY);
        model.addAttribute("projectId", projectId);
        model.addAttribute("tab", selectedTab);
        model.addAttribute("documentLabel", kind == BusinessDocumentKind.POLICY ? "정책서" : "표준용어");
        model.addAttribute("revisions", history.stream().map(item -> revisionView(
                item, editorNames.get(item.createdBy()), item.revisionNo() == history.get(0).revisionNo())).toList());
        model.addAttribute("selectedRevision", revisionView(selected,
                editorNames.get(selected.createdBy()), selected.revisionNo() == history.get(0).revisionNo()));
        model.addAttribute("changes", documents.changes(kind,
                previous == null ? "" : previous.content(), selected.content()));
        model.addAttribute("initialRevision", previous == null);
        return "artifacts/business-language-history";
    }

    @PostMapping("/history/restore")
    public String restore(@PathVariable String projectId,
                          @RequestParam(defaultValue = "policy") String tab,
                          @RequestParam int revisionNo,
                          @AuthenticationPrincipal BuilderUser me,
                          RedirectAttributes flash) {
        String selectedTab = "terms".equals(tab) ? "terms" : "policy";
        BusinessDocumentKind kind = "terms".equals(selectedTab)
                ? BusinessDocumentKind.STANDARD_TERMS : BusinessDocumentKind.POLICY;
        try {
            documents.restore(projectId, kind, revisionNo, me.accountId());
            flash.addFlashAttribute("message", "%d차 개정 내용을 새 개정본으로 복원했습니다.".formatted(revisionNo));
            return redirect(projectId, selectedTab, false);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return "redirect:/projects/%s/artifacts/business-language/history?tab=%s&revision=%d"
                    .formatted(projectId, selectedTab, revisionNo);
        }
    }

    @PostMapping("/seed")
    public String seed(@PathVariable String projectId, @AuthenticationPrincipal BuilderUser me,
                       RedirectAttributes flash) {
        try {
            seeds.start(projectId, me.accountId());
            flash.addFlashAttribute("message", "정책서와 표준용어 초안을 만들기 시작했습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return redirect(projectId, "policy", false);
    }

    @PostMapping("/policy")
    public String savePolicy(@PathVariable String projectId, @RequestParam String content,
                             @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        try {
            documents.savePolicy(projectId, content, me.accountId());
            flash.addFlashAttribute("message", "정책서를 저장했습니다.");
            return redirect(projectId, "policy", false);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return redirect(projectId, "policy", true);
        }
    }

    @PostMapping("/terms/new")
    public String addTerm(@PathVariable String projectId,
                          @RequestParam String term,
                          @RequestParam(required = false) String meaning,
                          @RequestParam(required = false) String aliases,
                          @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        try {
            documents.addTerm(projectId, row(term, meaning, aliases), me.accountId());
            flash.addFlashAttribute("message", "표준용어를 추가했습니다.");
            return redirect(projectId, "terms", false);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return termsRedirect(projectId, "newTerm=true");
        }
    }

    @PostMapping("/terms/{termIndex}")
    public String updateTerm(@PathVariable String projectId, @PathVariable int termIndex,
                             @RequestParam String term,
                             @RequestParam(required = false) String meaning,
                             @RequestParam(required = false) String aliases,
                             @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        try {
            documents.updateTerm(projectId, termIndex, row(term, meaning, aliases), me.accountId());
            flash.addFlashAttribute("message", "표준용어를 수정했습니다.");
            return redirect(projectId, "terms", false);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return termsRedirect(projectId, "editTerm=" + termIndex);
        }
    }

    @PostMapping("/terms/{termIndex}/delete")
    public String deleteTerm(@PathVariable String projectId, @PathVariable int termIndex,
                             @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        try {
            documents.deleteTerm(projectId, termIndex, me.accountId());
            flash.addFlashAttribute("message", "표준용어를 삭제했습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return redirect(projectId, "terms", false);
    }

    private BusinessDocumentSeed seedsState(String projectId) {
        return documents.seed(projectId).orElse(null);
    }

    private String updatedByName(BusinessDocument document) {
        if (document.updatedBy() == null) return "—";
        return accounts.selectById(document.updatedBy()).map(Account::getName).orElse("—");
    }

    private Map<String, String> editorNames(List<BusinessDocumentRevision> revisions) {
        Map<String, String> names = new LinkedHashMap<>();
        revisions.stream().map(BusinessDocumentRevision::createdBy).distinct().forEach(accountId -> {
            if (accountId == null) return;
            names.put(accountId, accounts.selectById(accountId).map(Account::getName).orElse("—"));
        });
        return names;
    }

    private static RevisionView revisionView(BusinessDocumentRevision revision, String editorName, boolean current) {
        return new RevisionView(revision.revisionNo(), revisionTypeLabel(revision.changeType()),
                revision.createdAt(), editorName == null ? "—" : editorName, current);
    }

    private static String revisionTypeLabel(BusinessDocumentRevisionType type) {
        return switch (type) {
            case INITIAL_DRAFT -> "최초 생성";
            case EDIT -> "직접 수정";
            case RESTORE -> "이전 버전 복원";
        };
    }

    static String seedFailureMessage(String reason) {
        if (reason == null || reason.isBlank()) return "초안을 만드는 중 오류가 발생했습니다. 잠시 뒤 다시 만들어 주세요.";
        return switch (reason) {
            case "NO_CREDENTIAL" -> "Claude 계정 연결 정보를 찾지 못했습니다. 계정을 연결한 뒤 다시 만들어 주세요.";
            case "CREDENTIAL_LOST" -> "Claude 계정 연결이 만료되었습니다. 계정을 다시 연결한 뒤 만들어 주세요.";
            case "TIMED_OUT" -> "초안 작성 시간이 초과되었습니다. 잠시 뒤 다시 만들어 주세요.";
            case "AI_EXECUTION_FAILED" -> "Claude가 초안 작성을 완료하지 못했습니다. 연결 상태를 확인한 뒤 다시 만들어 주세요.";
            case "INVALID_POLICY" -> "작성된 정책서에서 항목을 확인하지 못했습니다. 초안을 다시 만들어 주세요.";
            case "INVALID_STANDARD_TERMS" -> "작성된 표준용어에서 용어 항목을 확인하지 못했습니다. 초안을 다시 만들어 주세요.";
            case "INVALID_SOURCE_REFERENCES", "INVALID_RESPONSE" ->
                    "작성된 초안의 근거 또는 문서 형식을 확인하지 못했습니다. 초안을 다시 만들어 주세요.";
            case "INCOMPLETE_SOURCE_COVERAGE" ->
                    "저장소의 업무 문서와 화면 자료를 모두 확인하지 못했습니다. 초안을 다시 만들어 주세요.";
            case "INPUT_OUTPUT_FAILED" -> "저장소의 업무 문서나 화면 자료를 읽지 못했습니다. 저장소 상태를 확인한 뒤 다시 만들어 주세요.";
            case "SAVE_FAILED", "REFERENCE_SERIALIZATION_FAILED", "STATE_CHANGED" ->
                    "작성된 초안을 저장하지 못했습니다. 잠시 뒤 다시 만들어 주세요.";
            case "QUEUE_REJECTED" -> "현재 처리 중인 AI 작업이 많습니다. 잠시 뒤 다시 만들어 주세요.";
            case "SERVER_RESTARTED" -> "서버가 다시 시작되어 초안 만들기가 중단되었습니다. 다시 만들어 주세요.";
            default -> "초안을 만드는 중 예상하지 못한 오류가 발생했습니다. 잠시 뒤 다시 만들어 주세요.";
        };
    }

    private List<TermView> termViews(String projectId, BusinessDocument document) {
        Map<String, String> names = new LinkedHashMap<>();
        List<StandardTermAudit> rows = documents.termAudits(projectId, document);
        for (StandardTermAudit row : rows) {
            if (row.updatedBy() == null || names.containsKey(row.updatedBy())) continue;
            names.put(row.updatedBy(), accounts.selectById(row.updatedBy()).map(Account::getName).orElse("—"));
        }
        return java.util.stream.IntStream.range(0, rows.size())
                .mapToObj(index -> {
                    StandardTermAudit row = rows.get(index);
                    StandardTerm term = row.value();
                    return new TermView(index, term.term(), term.meaning(), term.aliases(),
                            row.updatedAt(), row.updatedBy() == null ? "—" : names.get(row.updatedBy()));
                }).toList();
    }

    private static StandardTerm row(String term, String meaning, String aliases) {
        return new StandardTerm(term, meaning, aliases, "");
    }

    private static String redirect(String projectId, String tab, boolean edit) {
        return "redirect:/projects/%s/artifacts/business-language?tab=%s%s"
                .formatted(projectId, tab, edit ? "&edit=true" : "");
    }

    private static String termsRedirect(String projectId, String query) {
        return "redirect:/projects/%s/artifacts/business-language?tab=terms&%s".formatted(projectId, query);
    }

    public record RevisionView(int revisionNo, String typeLabel, java.time.Instant createdAt,
                               String editorName, boolean current) {
    }

    public record TermView(int index, String term, String meaning, String aliases,
                           java.time.Instant updatedAt, String updatedByName) {
    }
}
