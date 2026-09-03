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
class BusinessLanguageTermRowScreenTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired BusinessDocumentMapper documents;
    @Autowired BusinessDocumentRevisionMapper revisions;
    @Autowired BusinessDocumentService service;
    @Autowired BusinessLanguageMarkdown markdown;
    @Autowired ProjectMapper projects;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired SecretSealer sealer;

    @Test
    void 표준용어는_선택한_행만_수정하고_행별_수정정보와_삭제버튼을_보여준다() throws Exception {
        Project project = readyProject();
        BuilderUser editor = superUser();
        String initial = markdown.termsMarkdown(java.util.List.of(
                new StandardTerm("회원", "서비스 가입자", "가입자", ""),
                new StandardTerm("판매처", "결제를 받는 곳", "가맹점", "")));
        documents.upsert(project.getId(), BusinessDocumentKind.POLICY, "## 정책\n내용\n", "[]", editor.accountId());
        documents.upsert(project.getId(), BusinessDocumentKind.STANDARD_TERMS, initial, "[]", editor.accountId());
        service.recordInitialRevision(project.getId(), BusinessDocumentKind.STANDARD_TERMS,
                initial, "[]", editor.accountId());

        String listHtml = mvc.perform(get(url(project) + "?tab=terms").with(user(editor)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(listHtml)
                .contains("수정일시", "수정자", "회원 수정", "회원 삭제", "표준용어 추가")
                .contains("data-term-search-empty", "검색 결과가 없습니다")
                .contains(accounts.selectById(editor.accountId()).orElseThrow().getName());

        String editHtml = mvc.perform(get(url(project) + "?tab=terms&editTerm=0").with(user(editor)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(editHtml).contains("term-edit-form-0").doesNotContain("term-edit-form-1");

        mvc.perform(post(url(project) + "/terms/0")
                        .param("term", "회원").param("meaning", "서비스 이용자")
                        .param("aliases", "가입자, 사용자")
                        .with(user(editor)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(url(project) + "?tab=terms"));

        assertThat(documents.selectOne(project.getId(), BusinessDocumentKind.STANDARD_TERMS)
                .orElseThrow().content()).contains("서비스 이용자", "판매처");

        mvc.perform(post(url(project) + "/terms/1/delete").with(user(editor)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(url(project) + "?tab=terms"));

        assertThat(markdown.terms(documents.selectOne(project.getId(), BusinessDocumentKind.STANDARD_TERMS)
                .orElseThrow().content())).extracting(StandardTerm::term).containsExactly("회원");
        assertThat(revisions.selectAll(project.getId(), BusinessDocumentKind.STANDARD_TERMS)).hasSize(3);
    }

    private Project readyProject() {
        var sealed = sealer.seal("glpat-business-language-term-row-test");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, "표준용어 행 편집 검증", "https://gitlab.example.com/x.git",
                "main", "TR", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }

    private static String url(Project project) {
        return "/projects/" + project.getId() + "/artifacts/business-language";
    }
}
