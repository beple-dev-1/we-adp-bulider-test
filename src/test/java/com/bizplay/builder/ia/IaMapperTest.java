package com.bizplay.builder.ia;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class IaMapperTest extends AbstractDbTest {

    @Autowired IaMapper mapper;
    @Autowired ProjectMapper projects;
    @Autowired AccountMapper accounts;

    @Test
    void depth_다섯_칸과_화면_연결을_DB에서_읽고_낙관적_잠금으로_고친다() {
        String projectId = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(projectId, "IA 매퍼 시험", "https://gitlab.example.com/ia.git", "main",
                "PS", new byte[]{1}, new byte[]{2}));
        String accountId = accounts.selectByLoginId("admin").orElseThrow().getId();
        String structureId = ids.next(IdSequence.Kind.IA_STRUCTURE);
        mapper.insertStructure(new IaStructure(structureId, projectId, "backoffice", IaStructure.State.DRAFT,
                0, 0, "a".repeat(64), null, null, null, null, null, null, accountId));
        String rowId = ids.next(IdSequence.Kind.IA_ROW);
        mapper.insertRow(new IaRow(rowId, structureId, 10, "approval/document/detail",
                "전자결재", "결재 문서", "상세", null, null, null, null, "사용자", "업무", "화면",
                "bo-appr-detail", null, accountId));

        IaRow saved = mapper.selectRows(structureId).get(0);
        assertThat(saved.depths()).containsExactly("전자결재", "결재 문서", "상세");
        assertThat(mapper.selectScreenLinks(projectId)).containsExactly(
                new IaScreenLink("backoffice", "bo-appr-detail", "전자결재 > 결재 문서 > 상세",
                        0, IaStructure.State.DRAFT));

        assertThat(mapper.bumpVersion(structureId, 0, accountId)).isOne();
        assertThat(mapper.bumpVersion(structureId, 0, accountId)).isZero();
        assertThat(mapper.selectStructureById(structureId).version()).isOne();
    }
}
