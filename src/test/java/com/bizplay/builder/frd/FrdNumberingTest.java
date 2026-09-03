package com.bizplay.builder.frd;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FRD 의 번호와 표 계약.
 *
 * <p>⭐ <b>여기가 초록이면 FRD 가 사슬 넷과 FK 없이 혼자 선다</b>는 뜻이다 —
 * 받은 문서도 요구사항도 없는 프로젝트에 FRD 가 앉는 것이 이 시험의 심장이다.
 */
class FrdNumberingTest extends AbstractDbTest {

    @Autowired FrdMapper frds;
    @Autowired FrdScreenMapper screens;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;

    @Test
    void 받은_문서가_하나도_없는_프로젝트에도_FRD_가_앉는다() {
        Project p = readyProject("탐나는전");

        String id = seedFrd(p, "전자결재 상신 임시저장 지원");

        Frd found = frds.selectById(id);
        assertThat(found.number()).isEqualTo(1);
        assertThat(found.state()).isEqualTo(Frd.State.ANALYZING);
        assertThat(found.sourceKind()).isEqualTo(Frd.SourceKind.PASTED);
        assertThat(found.sourceRef()).as("붙여넣기는 가리킬 것이 없다").isNull();
    }

    @Test
    void 번호는_프로젝트마다_1번부터고_지워도_재사용하지_않는다() {
        Project a = readyProject("탐나는전");
        Project b = readyProject("지역화폐");

        assertThat(frds.allocateNumber(a.getId())).isEqualTo(1);
        assertThat(frds.allocateNumber(a.getId())).isEqualTo(2);
        assertThat(frds.allocateNumber(b.getId())).as("프로젝트마다 1번부터다").isEqualTo(1);
        assertThat(frds.allocateNumber(a.getId())).as("b 가 집어도 a 는 제 줄을 잇는다").isEqualTo(3);
    }

    @Test
    void 화면_0장인_FRD_가_정상이다() {
        Project p = readyProject("탐나는전");
        String id = seedFrd(p, "야간 정산 배치 주기 변경");

        assertThat(screens.selectByFrdId(id)).isEmpty();
    }

    @Test
    void 같은_화면을_한_FRD_에_두_번_담지_못한다() {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "전자결재 상신 임시저장 지원");
        screens.insert(FrdScreen.picked(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "wv-appr-write", "결재 문서 작성", "wv-appr-write", null, "버튼이 없다"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        screens.insert(FrdScreen.picked(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                                "wv-appr-write", "결재 문서 작성", "wv-appr-write", null, "또 담았다")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private String seedFrd(Project project, String title) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(id, project.getId(), frds.allocateNumber(project.getId()),
                title, "상신 화면에서 작성 중인 문서를 임시 저장할 수 있어야 한다.", null));
        return id;
    }

    /**
     * ⚠ {@code Project.register}·{@code markReady} 는 이 저장소에 없다 — 2026-08-15 에
     * JPA 엔티티에서 MyBatis 값 묶음으로 바뀌면서 상태 변경 메서드가 전부 없어졌다
     * ({@code Project.java} 머리의 경고). {@code ProjectPickTest.seatProject} 를 그대로 베낀다:
     * {@code Project.create} 로 RECEIVING 상태로 앉힌 뒤 {@code ProjectMapper.updateState} 로
     * READY 로 올리고 다시 읽는다.
     */
    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/" + name + ".git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }
}
