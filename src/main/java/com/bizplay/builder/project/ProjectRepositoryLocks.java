package com.bizplay.builder.project;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** 같은 프로젝트의 기본 브랜치를 바꾸는 Git 작업을 서버 안에서 한 줄로 세운다. */
@Component
public class ProjectRepositoryLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public void run(String projectId, Runnable work) {
        withLock(projectId, () -> {
            work.run();
            return null;
        });
    }

    public <T> T withLock(String projectId, Supplier<T> work) {
        ReentrantLock lock = locks.computeIfAbsent(projectId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
