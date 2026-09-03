package com.bizplay.builder.solution;

import java.time.LocalDate;
import java.util.List;

/**
 * 화면 한 장이 기획 저장소에서 바뀌어 온 이력 — <b>클론의 {@code git log} 가 정본이다.</b>
 *
 * <p>목록의 버전은 별도 메뉴구조도 판 번호가 아니라 이 화면 파일이 Git 에 올라온 순서를
 * {@code v1}, {@code v2}처럼 표시한다. 같은 커밋에서 md와 html을 함께 고쳐도 한 번만 센다.
 *
 * <p>⚠ <b>없을 수 있다.</b> 클론이 얕거나({@code --depth}) git 이 안 돌면 비어 있다 —
 * 그때 화면은 날짜 자리를 「—」로 낸다. 이력이 없다고 화면이 안 뜨면 안 된다.
 *
 * @param changes 새것이 앞이다. {@code git log} 의 차례 그대로다
 */
public record ScreenHistory(List<Change> changes) {

    public static final ScreenHistory EMPTY = new ScreenHistory(List.of());

    /** 커밋 하나가 이 화면에 한 일. */
    public record Change(LocalDate date, String author, String subject) {
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** 화면 파일의 Git 변경 순서를 솔루션 목업 버전으로 표시한다. */
    public String versionLabel() {
        return isEmpty() ? "—" : "v" + changes.size();
    }

    /** 마지막으로 바뀐 날. 없으면 {@code null} — 화면이 「—」로 받는다. */
    public LocalDate lastDate() {
        return isEmpty() ? null : changes.get(0).date();
    }

    public String lastAuthor() {
        return isEmpty() ? null : changes.get(0).author();
    }

    /** 처음 올라온 날. {@code git log} 의 <b>맨 끝</b>이다. */
    public LocalDate firstDate() {
        return isEmpty() ? null : changes.get(changes.size() - 1).date();
    }
}
