package com.bizplay.builder.project;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.id.IdSequence;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class ProjectPaths {

    private final Path root;

    public ProjectPaths(BuilderProperties properties) {
        this.root = properties.dataRoot();
    }

    /**
     * 서버 클론 하나 — 워크트리의 뿌리다.
     * ⛔ 여기서 직접 고치지 않는다. 계획 2 가 여기서 워크트리를 딴다.
     */
    public Path cloneDir(String projectId) {
        return projectDir(projectId).resolve("clone");
    }

    /** 워크트리가 앉을 자리. 계획 2 가 쓴다. */
    public Path worktreeRoot(String projectId) {
        return projectDir(projectId).resolve("worktrees");
    }

    /**
     * FRD 하나가 계속 다시 쓰는 작업 자리.
     *
     * <p>화면 번호({@code FRD-025})가 아니라 바뀌지 않는 DB ID를 쓴다. 서버가 다시 떠도 같은
     * 경로를 계산해야 기존 워크트리를 되찾을 수 있기 때문이다.
     */
    public Path frdWorktree(String projectId, String frdId) {
        if (!IdSequence.isValidId(frdId)) {
            throw new IllegalArgumentException("FRD 번호의 꼴이 아니다: '%s'".formatted(frdId));
        }
        return worktreeRoot(projectId).resolve("frd-" + frdId);
    }

    /** 개발 완료 FRD를 기본 브랜치에 합칠 때만 쓰고 지우는 임시 워크트리. */
    public Path devRequestMergeWorktree(String projectId, String requestId) {
        if (!IdSequence.isValidId(requestId)) {
            throw new IllegalArgumentException("개발요청서 번호의 꼴이 아니다: '%s'".formatted(requestId));
        }
        return worktreeRoot(projectId).resolve("dev-request-merge-" + requestId);
    }

    /** 확정한 메뉴구조도 스냅샷이 기획 저장소에 게시되는 자리. */
    public Path iaFile(String projectId, String systemCode) {
        if (systemCode == null || !systemCode.matches("^[A-Za-z0-9_-]+$")) {
            throw new IllegalArgumentException("시스템 코드의 꼴이 아닙니다: " + systemCode);
        }
        return cloneDir(projectId).resolve("core").resolve(systemCode).resolve("ia.md");
    }

    /**
     * 올린 받은 문서 원본이 앉는 자리.
     *
     * <p>⛔ <b>클론 안이 아니다.</b> 받은 문서는 기획 레포로 나가는 물건이 아니라
     * 서버가 들고 있는 원본이다 — 클론 폴더에 두면 git 이 그것을 보게 된다.
     *
     * <p>⚠ <b>자리 글자를 만드는 곳은 여기 하나여야 한다</b>(계획 2 Task 2).
     * 파일 이름을 붙이는 쪽은 {@code IntakeService.safeFileName} 이 따로 걷어낸다.
     */
    public Path receivedDir(String projectId) {
        return projectDir(projectId).resolve("received");
    }

    /** 개발요청서와 함께 개발팀에 보낼 첨부파일이 대기하는 자리. */
    public Path devRequestAttachmentDir(String projectId) {
        return projectDir(projectId).resolve("dev-request-attachments");
    }

    /**
     * 개발에 나갈 <b>꾸러미 한 채</b>가 구워지는 자리 — 디벨롭과의 계약서다.
     *
     * <p>⛔ <b>클론 안이 아니다.</b> 꾸러미는 기획 레포로 밀 물건이 아니라 밖으로 나갈 사본이고,
     * 클론 폴더에 두면 git 이 그것을 보게 된다({@link #receivedDir} 와 같은 까닭이다).
     *
     * <p>⚠ <b>다시 구우면 통째로 갈아 낀다.</b> 개발요청서 하나에 꾸러미 하나다 —
     * 판이 남으면 어느 것을 보냈는지 알 수 없다. 무엇을 보냈나는 {@code manifest.json} 의
     * 지문과 전송 이력이 답한다.
     */
    public Path devRequestPackageDir(String projectId, String requestId) {
        if (!IdSequence.isValidId(requestId)) {
            throw new IllegalArgumentException("개발요청서 번호의 꼴이 아니다: '%s'".formatted(requestId));
        }
        return projectDir(projectId).resolve("dev-request-packages").resolve(requestId);
    }

    /** 개발팀에 실제로 보낸 ZIP 원본. 기획 저장소가 아니라 Builder 데이터 영역에 둔다. */
    public Path devRequestPackageArchive(String projectId, String requestId, int requestNumber) {
        if (requestNumber < 1) {
            throw new IllegalArgumentException("개발요청서 번호는 1 이상이어야 합니다.");
        }
        return devRequestPackageDir(projectId, requestId)
                .resolve("DR-%03d.zip".formatted(requestNumber));
    }

    /**
     * AI 실행 하나가 쓰는 {@code CLAUDE_CONFIG_DIR}.
     *
     * <p>⛔ <b>사람마다가 아니라 실행마다다.</b> 같은 사람이 두 일을 동시에 돌릴 수 있는데
     * (잠기는 것은 사람이 아니라 「일」이다) 자리를 사람으로 잡으면 <b>먼저 끝난 실행의
     * {@code finally} 가 아직 도는 실행의 자격 파일을 지운다.</b>
     *
     * <p>⚠ 프로젝트 밑이 아니다 — 자격은 그 사람의 것이지 그 사업의 것이 아니다.
     */
    public Path runCredentialDir(String runId) {
        if (!IdSequence.isValidId(runId)) {
            throw new IllegalArgumentException("실행 번호의 꼴이 아니다: '%s'".formatted(runId));
        }
        return root.resolve("runs").resolve(runId).resolve("credentials");
    }

    /** 정책서·표준용어 초안 생성 한 건이 잠시 쓰는 Claude 자격 디렉터리. */
    public Path businessLanguageCredentialDir(String projectId) {
        return projectDir(projectId).resolve("business-language-run").resolve("credentials");
    }

    /**
     * 문서 처리 시도 하나가 쓰는 자리 — 밑에 {@code credentials} 와 {@code work} 가 난다.
     *
     * <p>⛔ <b>{@link #runCredentialDir} 밑으로 넣지 마라.</b> 두 표가 서로 다른 시퀀스인데
     * <b>둘 다 일곱 자리</b>라서 {@code ai_run} 의 {@code 0000001} 과 문서 처리의 {@code 0000001} 이
     * 같은 {@code runs/0000001} 을 가리킨다 — 먼저 끝난 쪽의 {@code finally} 가
     * <b>아직 도는 쪽의 자격 파일을 지운다.</b> 번호가 같아도 자리는 갈라야 한다.
     *
     * <p>⚠ 프로젝트 밑이 아니다 — 자격은 그 사람의 것이지 그 사업의 것이 아니다.
     */
    public Path documentRunDir(String runId) {
        if (!IdSequence.isValidId(runId)) {
            throw new IllegalArgumentException("실행 번호의 꼴이 아니다: '%s'".formatted(runId));
        }
        return root.resolve("doc-runs").resolve(runId);
    }

    /**
     * ⛔ <b>꼴을 여기서 다시 잰다.</b> 번호가 {@code Long} 이던 때는 타입이 막아 줬다 —
     * 이제 글자라서 {@code ".."} 나 {@code "a/b"} 가 오면 {@code resolve} 가 순순히 받아
     * <b>data-root 밖을 가리키는 경로</b>가 난다. 부르는 자리가 지금은 DB 에서 온 값뿐이지만
     * 이 메서드는 공개돼 있고 계획 2 가 워크트리에서 다시 쓴다.
     */
    private Path projectDir(String projectId) {
        if (!IdSequence.isValidId(projectId)) {
            throw new IllegalArgumentException("프로젝트 번호의 꼴이 아니다: '%s'".formatted(projectId));
        }
        return root.resolve("projects").resolve(projectId);
    }
}
