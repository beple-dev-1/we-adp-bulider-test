package com.bizplay.builder.intake;

import com.bizplay.builder.ai.DocumentUnderstandingClient;
import com.bizplay.builder.ai.DocumentUnderstandingException;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.intake.DocumentProcessingRun.Kind;
import com.bizplay.builder.intake.DocumentProcessingRun.State;
import com.bizplay.builder.intake.ReceivedDocument.ContentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 서버가 글자를 못 뽑은 문서를 <b>멀티모달 AI 가 읽는다</b> — 스캔 PDF 와 그림 파일이 그것이다.
 *
 * <p><b>2026-08-15 에 이 일꾼이 하던 일 절반이 없어졌다.</b> 그전에는 <b>모든</b> 받은 문서를
 * {@code claude} 로 1차 정리했다. 지금은 안 한다 —
 * <pre>
 * 직접 입력              → (아무 AI 도 안 부른다)              등록 완료
 * 첨부 · 서버 추출 성공  → (아무 AI 도 안 부른다)              등록 완료
 * 첨부 · 글자가 안 나옴  → 내용 분석 대기 → 내용 분석 중 → 등록 완료
 * </pre>
 * ⛔ <b>{@code normalize()} 를 되살리지 마라.</b> 사람이 친 글을 AI 가 고쳐 놓는 것이 문제였고,
 * 「원문 그대로 보존한다」와 정면으로 부딪혔다.
 *
 * <p>★ <b>별도 빈이다.</b> ⛔ {@link IntakeService} 나 {@link DocumentProcessingService} 안에 두지 마라 —
 * 자기 자신을 부르는 꼴이라 프록시를 안 타서 {@code @Async} 가 <b>아예 발동하지 않는다.</b>
 * 몇 분짜리 일이 문서 등록 요청을 그대로 막는다({@code AiRunWorker} 가 같은 함정을 적어 뒀다).
 *
 * <p>⛔ <b>읽어 낸 글을 문서 내용 자리에 곧장 앉히지 마라.</b> 그 자리는 <b>사람이 확인한 글</b>이다 —
 * 확인을 건너뛰면 AI 가 잘못 읽은 표가 요구사항 분석에 그대로 들어간다.
 */
@Component
public class DocumentProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingWorker.class);

    private final DocumentProcessingService processing;
    private final DocumentTextExtractor extractor;
    private final DocumentUnderstandingClient understanding;

    public DocumentProcessingWorker(DocumentProcessingService processing,
                                    DocumentTextExtractor extractor,
                                    DocumentUnderstandingClient understanding) {
        this.processing = processing;
        this.extractor = extractor;
        this.understanding = understanding;
    }

    /**
     * ⛔ <b>최상위를 {@code try/catch} 로 감싸라.</b> {@code void @Async} 의 예외는 호출자에게 안 가고
     * 로그만 남는다 — 빠뜨리면 문서가 <b>영원히 「내용 분석 중」</b>으로 굳고
     * 화면은 「완료되면 알림으로 안내합니다」를 영원히 띄운다.
     *
     * <p>⚠ {@code aiExecutor} 를 AI 실행과 나눠 쓴다. 서버가 동시에 감당할 수 있는 수가 같은 값이라
     * 따로 두면 상한이 둘로 갈려 의미가 없어진다.
     */
    @Async("aiExecutor")
    public void process(String intakeId) {
        try {
            execute(intakeId);
        } catch (RuntimeException unexpected) {
            log.warn("내용 분석이 예상 못 한 이유로 끝났다 intakeId={}", intakeId, unexpected);
            rescue(intakeId);
        }
    }

    private void execute(String intakeId) {
        var materials = processing.materials(intakeId);

        // ⛔ 줄 서 있는 것만 가져간다. 직접 입력과 서버 추출이 된 문서는 이미 등록 완료라
        //    여기 올 일이 없고, 「다시 시도」가 이 자리를 다시 부를 수 있어 한 번 더 본다.
        if (materials.state() != ContentState.QUEUED) {
            return;
        }
        if (materials.sourcePath() == null) {
            // 파일이 없는데 줄에 섰다 — 등록 판정이 어긋난 것이다. 굳히지 말고 오류로 앉힌다.
            processing.markFailed(materials.documentId(), "읽을 파일이 없다");
            return;
        }

        String mediaType = mediaTypeOf(materials.sourcePath());
        if (mediaType == null) {
            processing.markFailed(materials.documentId(),
                    "내용 분석이 읽을 수 있는 종류가 아니다");
            return;
        }
        if (!understanding.available()) {
            // ⛔ 「저쪽이 터졌다」와 섞지 마라 — 고칠 자리가 설정이라는 것을 사람이 알아야 한다.
            processing.markFailed(materials.documentId(),
                    "내용 분석 설정이 아직 없습니다 — 관리자에게 문의해 주세요");
            return;
        }

        String runId;
        try {
            runId = processing.openRun(materials.documentId(), Kind.UNDERSTAND, Instant.now());
        } catch (DataIntegrityViolationException alreadyLive) {
            // ⚠ 같은 문서를 두 번 밀었다 — 부분 유일 인덱스가 막았다. 앞엣것이 돌고 있으니 그냥 나간다.
            log.info("이 문서의 내용 분석이 이미 돌고 있다 intakeId={}", intakeId);
            return;
        }
        processing.markState(materials.documentId(), ContentState.PROCESSING);

        // ⚠ 몇십 초짜리 일이다 — 아무 말도 안 하면 사람이 「눌리긴 한 건가」를 알 수 없다.
        Instant startedAt = Instant.now();
        log.info("내용 분석 시작 intakeId={} runId={} 종류={} 파일={}",
                intakeId, runId, mediaType, materials.sourcePath().getFileName());

        try {
            String read = understanding.read(materials.sourcePath(), mediaType);
            processing.finishRun(runId, State.SUCCEEDED, null, Instant.now());
            processing.saveUnderstood(materials.documentId(), read);
            log.info("내용 분석 끝 intakeId={} {}자 · {}초 — 요구사항 분석이 열렸다",
                    intakeId, read.length(),
                    java.time.Duration.between(startedAt, Instant.now()).toSeconds());
        } catch (IOException failed) {
            /*
             * ⛔ 실패를 한 덩어리로 뭉치지 마라 — 갈래마다 고칠 사람이 다르다(관리자·아무도·기획자).
             *    ⭐ 그렇다고 여기에 catch 사다리를 쌓지도 마라. 무엇이 잘못됐는지 아는 것은
             *      **저쪽에 말을 건 자리**이므로 사람에게 할 말도 거기서 정해 실어 온다
             *      (DocumentUnderstandingException.userMessage).
             *    ⚠ 2026-08-16 에 두 갈래를 다 겪었다 — 사내 CA 없는 자바(관리자 몫)와
             *      저쪽의 일시적 혼잡(아무도 안 고침).
             * ⛔ 실패 원문을 화면에 그대로 내지 않는다: 키가 섞여 나올 수 있어 가리개를 지난다.
             */
            log.warn("내용 분석이 실패했다 intakeId={} runId={} {}초 — {}",
                    intakeId, runId,
                    java.time.Duration.between(startedAt, Instant.now()).toSeconds(),
                    GitCommand.mask(String.valueOf(failed.getMessage())));
            processing.finishRun(runId, State.FAILED,
                    GitCommand.mask(String.valueOf(failed.getMessage())), Instant.now());
            processing.markFailed(materials.documentId(), userMessageOf(failed));
        }
    }

    /**
     * 화면에 낼 말. <b>저쪽에 말을 건 자리가 실어 온 것이 있으면 그것을 쓴다.</b>
     *
     * <p>⚠ 없으면(파일을 못 읽는 것 같은 우리 쪽 사고) 문서를 바꿔 보라고 한다 —
     * 그 갈래는 기획자가 손댈 수 있는 자리가 맞다.
     */
    private static String userMessageOf(IOException failed) {
        return failed instanceof DocumentUnderstandingException told
                ? told.userMessage()
                : "내용을 읽지 못했습니다 — 다시 시도하거나 글로 옮겨 등록해 주세요";
    }

    /**
     * 저쪽에 알려 줄 종류. ⚠ <b>확장자가 아니라 앞 바이트로 가른다</b> —
     * 이름을 {@code .pdf} 로 바꾼 그림 파일이 온다.
     */
    private String mediaTypeOf(Path file) {
        try {
            byte[] head = extractor.readHead(file);
            if (extractor.isPdf(head)) {
                return "application/pdf";
            }
            return extractor.imageMediaType(head);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** 예상 못 한 예외로 떨어졌을 때 굳는 것만은 막는다. */
    private void rescue(String intakeId) {
        try {
            var materials = processing.materials(intakeId);
            if (materials.state() == ContentState.PROCESSING || materials.state() == ContentState.QUEUED) {
                processing.markFailed(materials.documentId(),
                        "내용 분석이 끝나지 못했습니다 — 다시 시도해 주세요");
            }
        } catch (RuntimeException alsoBroken) {
            log.warn("굳은 문서를 오류로 옮기지도 못했다 intakeId={}", intakeId, alsoBroken);
        }
    }
}
