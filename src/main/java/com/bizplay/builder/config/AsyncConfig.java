package com.bizplay.builder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    private final BuilderProperties properties;

    public AsyncConfig(BuilderProperties properties) {
        this.properties = properties;
    }

    /**
     * AI 실행 전용 실행기.
     *
     * <p>⛔ <b>{@code cloneExecutor} 를 돌려쓰지 마라.</b> AI 실행은 몇 분짜리라 클론 큐를 막는다.
     *
     * <p>⛔ <b>크기를 1 로 두지 마라.</b> 「한 번에 하나」의 뜻이 밀기와 AI 실행에서 다르다 —
     * 밀기는 전체가 하나지만 <b>AI 실행은 「한 일」에 하나</b>다. 다른 BRD·다른 사람은
     * <b>동시에 돌아야 한다.</b> 막는 것은 실행기가 아니라 부분 유일 인덱스다.
     * 1 로 두면 열 명이 한 줄로 서서 앞사람 10분을 기다린다.
     *
     * <p>⛔ <b>숫자를 여기 박지 마라</b> — 크기와 대기줄은 설치 설정에서 온다.
     *
     * <p>⚠ 대기줄이 차면 제출이 <b>거절된다</b>({@code AbortPolicy} 가 기본값이다).
     * 그 실행을 실패로 닫는 일은 {@code AiRunService.submitWorker} 가 한다 —
     * {@code RUNNING} 인 채로 버려두면 그 일은 유일 인덱스에 막혀 영영 다시 못 돈다.
     */
    @Bean("aiExecutor")
    public ThreadPoolTaskExecutor aiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.aiConcurrency());
        executor.setMaxPoolSize(properties.aiConcurrency());
        executor.setQueueCapacity(properties.aiQueueCapacity());
        executor.setThreadNamePrefix("ai-run-");
        executor.initialize();
        return executor;
    }

    @Bean("cloneExecutor")
    public ThreadPoolTaskExecutor cloneExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("clone-");
        executor.initialize();
        return executor;
    }
}
