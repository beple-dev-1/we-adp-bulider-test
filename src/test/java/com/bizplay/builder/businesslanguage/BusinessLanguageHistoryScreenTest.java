package com.bizplay.builder.businesslanguage;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class BusinessLanguageHistoryScreenTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired BusinessDocumentMapper documents;
    @Autowired BusinessDocumentRevisionMapper revisions;
    @Autowired BusinessDocumentService service;
    @Autowired ProjectMapper projects;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired SecretSealer sealer;

    @Test
    void 정책서_수정이력을_비교하고_과거_개정본을_새_개정으로_복원한다() throws Exception {
        Project project = readyProject();
        BuilderUser editor = superUser();
        String first = "## 회원 가입\n만 14세 이상 가입할 수 있다.\n";
        String second = "## 회원 가입\n만 15세 이상 가입할 수 있다.\n";
        documents.upsert(project.getId(), BusinessDocumentKind.POLICY, first, "[]", editor.accountId());
        service.recordInitialRevision(project.getId(), BusinessDocumentKind.POLICY, first, "[]", editor.accountId());
        service.savePolicy(project.getId(), second, editor.accountId());

        String html = mvc.perform(get("/projects/" + project.getId()
                        + "/artifacts/business-language/history?tab=policy").with(user(editor)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("2차 개정", "이전 내용", "만 14세 이상", "변경 내용", "만 15세 이상");

        mvc.perform(post("/projects/" + project.getId()
                        + "/artifacts/business-language/history/restore")
                        .param("tab", "policy").param("revisionNo", "1")
                        .with(user(editor)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + project.getId()
                        + "/artifacts/business-language?tab=policy"));

        assertThat(documents.selectOne(project.getId(), BusinessDocumentKind.POLICY).orElseThrow().content())
                .isEqualTo(first);
        assertThat(revisions.selectAll(project.getId(), BusinessDocumentKind.POLICY))
                .extracting(BusinessDocumentRevision::changeType)
                .containsExactly(BusinessDocumentRevisionType.RESTORE,
                        BusinessDocumentRevisionType.EDIT, BusinessDocumentRevisionType.INITIAL_DRAFT);
    }

    private Project readyProject() {
        var sealed = sealer.seal("glpat-business-language-history-test");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, "정책 이력 검증", "https://gitlab.example.com/x.git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
