package com.bizplay.builder.devrequest;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 운영 서버에서 개발요청 상태를 일정 간격으로 확인한다. */
@Component
@Profile("!test")
public class DevelopmentStatusSchedule {

    private final DevelopmentStatusSynchronizer synchronizer;

    public DevelopmentStatusSchedule(DevelopmentStatusSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
    }

    @Scheduled(initialDelayString = "${builder.dev-request-status.initial-delay-ms:60000}",
               fixedDelayString = "${builder.dev-request-status.fixed-delay-ms:60000}")
    public void run() {
        synchronizer.syncOnce();
    }

    /** 테스트 프로필에서는 예약 실행 기반 자체를 열지 않는다. */
    @Configuration
    @EnableScheduling
    @Profile("!test")
    static class SchedulingConfiguration {
    }
}
