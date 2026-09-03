package com.bizplay.builder.businesslanguage;

import com.bizplay.builder.project.ProjectPaths;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class BusinessDocumentService {

    private final BusinessDocumentMapper documents;
    private final BusinessDocumentRevisionMapper revisions;
    private final BusinessDocumentSeedMapper seeds;
    private final BusinessLanguageMarkdown markdown;
    private final BusinessDocumentHistoryService history;
    private final ProjectPaths paths;

    public BusinessDocumentService(BusinessDocumentMapper documents, BusinessDocumentRevisionMapper revisions,
                                   BusinessDocumentSeedMapper seeds, BusinessLanguageMarkdown markdown,
                                   BusinessDocumentHistoryService history, ProjectPaths paths) {
        this.documents = documents;
        this.revisions = revisions;
        this.seeds = seeds;
        this.markdown = markdown;
        this.history = history;
        this.paths = paths;
    }

    public Optional<BusinessDocument> find(String projectId, BusinessDocumentKind kind) {
        return documents.selectOne(projectId, kind);
    }

    public Optional<BusinessDocumentSeed> seed(String projectId) {
        return seeds.selectOne(projectId);
    }

    public List<BusinessDocumentRevision> revisions(String projectId, BusinessDocumentKind kind) {
        return revisions.selectAll(projectId, kind);
    }

    public Optional<BusinessDocumentRevision> revision(String projectId, BusinessDocumentKind kind,
                                                        int revisionNo) {
        return revisions.selectOne(projectId, kind, revisionNo);
    }

    public List<BusinessDocumentChange> changes(BusinessDocumentKind kind, String before, String after) {
        return history.changes(kind, before, after);
    }

    public boolean hasDomainDocuments(String projectId) {
        Path domains = paths.cloneDir(projectId).resolve("domains").normalize();
        if (!Files.isDirectory(domains)) return false;
        try (var domainDirs = Files.list(domains)) {
            return domainDirs.filter(Files::isDirectory).anyMatch(this::containsMarkdown);
        } catch (IOException ignored) {
            return false;
        }
    }

    @Transactional
    public void savePolicy(String projectId, String content, String accountId) {
        updateExisting(projectId, BusinessDocumentKind.POLICY, markdown.normalizePolicy(content), accountId);
    }

    @Transactional
    public void saveTerms(String projectId, List<StandardTerm> terms, String accountId) {
        List<StandardTerm> valid = terms.stream()
                .filter(term -> term != null && term.term() != null && !term.term().isBlank()).toList();
        if (valid.isEmpty()) throw new IllegalArgumentException("표준용어를 한 개 이상 입력해 주세요.");
        updateExisting(projectId, BusinessDocumentKind.STANDARD_TERMS, markdown.termsMarkdown(valid), accountId);
    }

    public String policyHtml(BusinessDocument document) {
        return markdown.policyHtml(document.content());
    }

    public List<StandardTerm> terms(BusinessDocument document) {
        return markdown.terms(document.content());
    }

    /** 개정 이력을 거슬러 올라가 현재 각 용어가 마지막으로 바뀐 시점과 수정자를 찾는다. */
    public List<StandardTermAudit> termAudits(String projectId, BusinessDocument document) {
        List<BusinessDocumentRevision> history = revisions.selectAll(projectId, BusinessDocumentKind.STANDARD_TERMS);
        List<Map<String, StandardTerm>> snapshots = history.stream()
                .map(revision -> termsByName(revision.content()))
                .toList();
        return markdown.terms(document.content()).stream()
                .map(term -> audit(term, history, snapshots, document))
                .toList();
    }

    @Transactional
    public void addTerm(String projectId, StandardTerm term, String accountId) {
        BusinessDocument current = lockedTerms(projectId);
        List<StandardTerm> terms = new ArrayList<>(markdown.terms(current.content()));
        StandardTerm valid = validTerm(term);
        rejectDuplicate(terms, valid.term(), -1);
        terms.add(valid);
        updateExisting(current, markdown.termsMarkdown(terms), accountId);
    }

    @Transactional
    public void updateTerm(String projectId, int termIndex, StandardTerm term, String accountId) {
        BusinessDocument current = lockedTerms(projectId);
        List<StandardTerm> terms = new ArrayList<>(markdown.terms(current.content()));
        requireIndex(terms, termIndex);
        StandardTerm valid = validTerm(term);
        rejectDuplicate(terms, valid.term(), termIndex);
        terms.set(termIndex, valid);
        updateExisting(current, markdown.termsMarkdown(terms), accountId);
    }

    @Transactional
    public void deleteTerm(String projectId, int termIndex, String accountId) {
        BusinessDocument current = lockedTerms(projectId);
        List<StandardTerm> terms = new ArrayList<>(markdown.terms(current.content()));
        requireIndex(terms, termIndex);
        if (terms.size() == 1) throw new IllegalArgumentException("표준용어는 한 개 이상 있어야 합니다.");
        terms.remove(termIndex);
        updateExisting(current, markdown.termsMarkdown(terms), accountId);
    }

    public List<String> policyHeadings(BusinessDocument document) {
        return markdown.policyHeadings(document.content());
    }

    @Transactional
    public void recordInitialRevision(String projectId, BusinessDocumentKind kind, String content,
                                      String sourceRefs, String accountId) {
        revisions.insert(projectId, kind, revisions.nextRevisionNo(projectId, kind), content, sourceRefs,
                BusinessDocumentRevisionType.INITIAL_DRAFT, accountId);
    }

    @Transactional
    public void restore(String projectId, BusinessDocumentKind kind, int revisionNo, String accountId) {
        BusinessDocument current = documents.selectOneForUpdate(projectId, kind)
                .orElseThrow(() -> new IllegalStateException("복원할 문서를 찾을 수 없습니다."));
        BusinessDocumentRevision selected = revisions.selectOne(projectId, kind, revisionNo)
                .orElseThrow(() -> new IllegalArgumentException("선택한 수정이력을 찾을 수 없습니다."));
        if (current.content().equals(selected.content()) && current.sourceRefs().equals(selected.sourceRefs())) {
            throw new IllegalArgumentException("이미 현재 문서와 같은 내용입니다.");
        }
        int nextRevision = revisions.nextRevisionNo(projectId, kind);
        if (documents.updateDocument(projectId, kind, selected.content(), selected.sourceRefs(), accountId) != 1) {
            throw new IllegalStateException("문서를 복원하지 못했습니다.");
        }
        revisions.insert(projectId, kind, nextRevision, selected.content(), selected.sourceRefs(),
                BusinessDocumentRevisionType.RESTORE, accountId);
    }

    private void updateExisting(String projectId, BusinessDocumentKind kind, String content, String accountId) {
        BusinessDocument current = documents.selectOneForUpdate(projectId, kind)
                .orElseThrow(() -> new IllegalStateException("먼저 초안을 만들어 주세요."));
        updateExisting(current, content, accountId);
    }

    private void updateExisting(BusinessDocument current, String content, String accountId) {
        String projectId = current.projectId();
        BusinessDocumentKind kind = current.kind();
        if (current.content().equals(content)) return;
        int nextRevision = revisions.nextRevisionNo(projectId, kind);
        if (documents.updateContent(projectId, kind, content, accountId) != 1)
            throw new IllegalStateException("문서를 저장하지 못했습니다.");
        revisions.insert(projectId, kind, nextRevision, content, current.sourceRefs(),
                BusinessDocumentRevisionType.EDIT, accountId);
    }

    private BusinessDocument lockedTerms(String projectId) {
        return documents.selectOneForUpdate(projectId, BusinessDocumentKind.STANDARD_TERMS)
                .orElseThrow(() -> new IllegalStateException("먼저 표준용어 초안을 만들어 주세요."));
    }

    private StandardTermAudit audit(StandardTerm term, List<BusinessDocumentRevision> history,
                                    List<Map<String, StandardTerm>> snapshots, BusinessDocument document) {
        for (int index = 0; index < history.size(); index++) {
            BusinessDocumentRevision revision = history.get(index);
            StandardTerm revised = snapshots.get(index).get(term.term());
            if (!term.equals(revised)) continue;
            StandardTerm previous = index + 1 < history.size()
                    ? snapshots.get(index + 1).get(term.term()) : null;
            if (!Objects.equals(revised, previous)) {
                return new StandardTermAudit(term, revision.createdAt(), revision.createdBy());
            }
        }
        return new StandardTermAudit(term, document.updatedAt(), document.updatedBy());
    }

    private Map<String, StandardTerm> termsByName(String content) {
        Map<String, StandardTerm> rows = new LinkedHashMap<>();
        markdown.terms(content).forEach(term -> rows.putIfAbsent(term.term(), term));
        return rows;
    }

    private static StandardTerm validTerm(StandardTerm term) {
        if (term == null || term.term() == null || term.term().isBlank()) {
            throw new IllegalArgumentException("표준용어를 입력해 주세요.");
        }
        return new StandardTerm(term.term().strip(), clean(term.meaning()), clean(term.aliases()), "");
    }

    private static void rejectDuplicate(List<StandardTerm> terms, String name, int exceptIndex) {
        for (int index = 0; index < terms.size(); index++) {
            if (index != exceptIndex && terms.get(index).term().equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("이미 등록된 표준용어입니다.");
            }
        }
    }

    private static void requireIndex(List<StandardTerm> terms, int termIndex) {
        if (termIndex < 0 || termIndex >= terms.size()) {
            throw new IllegalArgumentException("수정할 표준용어를 찾지 못했습니다.");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private boolean containsMarkdown(Path directory) {
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().toLowerCase().endsWith(".md"));
        } catch (IOException ignored) {
            return false;
        }
    }
}
