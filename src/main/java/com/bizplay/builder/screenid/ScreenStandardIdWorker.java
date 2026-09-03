package com.bizplay.builder.screenid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 클론·저장소 업데이트 뒤에 표준 화면ID 를 채운다.
 *
 * <p>★ <b>별도 빈이다.</b> ⛔ {@link ScreenStandardIdService} 안에 두지 마라 — 자기 자신을
 * 부르는 꼴이라 프록시를 안 타서 {@code @Async} 가 <b>아예 발동하지 않는다.</b>
 *
 * <p>⛔ <b>채번 실패가 클론을 실패시키면 안 된다.</b> 클론은 성공했는데 「받는 중」에 굳거나
 * 「실패」로 뜨면 사람이 할 수 있는 일이 없다. <b>삼키고 로그만 남긴다.</b>
 *
 * <p>⚠ {@link ScreenStandardIdService#assign} 은 트랜잭션이 없다 — 같은 프로젝트에 두 번이
 * 겹치면 {@code DuplicateKeyException}(런타임 예외) 이 날 수 있다. 그 예외도 여기서 삼킨다 —
 * 「저장소 업데이트」가 곧 재시도이니, 이번에 진 쪽은 다음 저장소 업데이트 때 다시 채우면 된다.
 *
 * <p>⚠ <b>재시도 화면이 따로 없는 것은 일부러다.</b> 「저장소 업데이트」가 곧 재시도다 —
 * 채번은 <b>없는 화면만</b> 채우므로 몇 번 눌러도 안전하다.
 *
 * <p>⛔ <b>{@code cloneExecutor} 에 얹지 마라.</b> {@code AsyncConfig} 의 {@code aiExecutor}
 * javadoc 이 이미 못박아 뒀다 — 「{@code cloneExecutor} 를 돌려쓰지 마라. AI 실행은 몇 분짜리라
 * 클론 큐를 막는다.」 여기서 도는 {@link ScreenStandardIdService#assign} 은 실물로 몇 분짜리
 * {@link BusinessAreaCoder}를 부르는 AI 실행이라 그 금지가 그대로 적용된다. 그래서
 * {@code aiExecutor} 를 쓴다 — 클론(최대 30분)과 채번(몇 분)이 스레드 4개를 다투면 서로를
 * 수십 분씩 기다리게 한다.
 */
@Component
public class ScreenStandardIdWorker {

    private static final Logger log = LoggerFactory.getLogger(ScreenStandardIdWorker.class);

    /** ⚠ 시험이 대역을 끼울 수 있게 함수형으로 받는다. */
    @FunctionalInterface
    public interface Assigner {
        int assign(String projectId, String accountId);
    }

    private final Assigner assigner;

    @Autowired
    public ScreenStandardIdWorker(ScreenStandardIdService service) {
        this((projectId, accountId) -> service.assign(projectId, accountId));
    }

    public ScreenStandardIdWorker(Assigner assigner) {
        this.assigner = assigner;
    }

    @Async("aiExecutor")
    public void assignQuietly(String projectId, String accountId) {
        try {
            int assigned = assigner.assign(projectId, accountId);
            if (assigned > 0) log.info("표준 화면ID 를 채웠다 projectId={} 장수={}", projectId, assigned);
        } catch (RuntimeException failure) {
            log.warn("표준 화면ID 채번에 실패했다. 저장소 업데이트가 곧 재시도다 projectId={}", projectId, failure);
        }
    }
}
