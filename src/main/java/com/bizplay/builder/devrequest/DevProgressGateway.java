package com.bizplay.builder.devrequest;

/** 개발요청 이슈를 읽어 현재 개발 라벨을 확인하는 경계. */
public interface DevProgressGateway {

    Inspection inspect(String projectId, String issueUrl, String deliveryKey);

    record Inspection(DevelopmentState state, String failure) {
        public static Inspection found(DevelopmentState state) {
            return new Inspection(state, null);
        }

        public static Inspection failed(String failure) {
            return new Inspection(null, failure);
        }

        public boolean succeeded() {
            return state != null;
        }
    }
}
