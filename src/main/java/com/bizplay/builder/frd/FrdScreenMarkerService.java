package com.bizplay.builder.frd;

import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.id.IdSequence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 화면 요소에 붙는 실행 마커를 저장하고 AI 대화에 전달할 정보를 제공한다. */
@Service
public class FrdScreenMarkerService {

    static final int MAX_SELECTOR_LENGTH = 2_000;
    static final int MAX_LABEL_LENGTH = 300;
    static final int MAX_DESCRIPTION_LENGTH = 4_000;

    private final FrdScreenMarkerMapper markers;
    private final FrdScreenMarkerHistoryMapper markerHistories;
    private final FrdScreenHistoryMapper screenHistories;
    private final IdSequence ids;

    public FrdScreenMarkerService(FrdScreenMarkerMapper markers,
                                  FrdScreenMarkerHistoryMapper markerHistories,
                                  FrdScreenHistoryMapper screenHistories,
                                  IdSequence ids) {
        this.markers = markers;
        this.markerHistories = markerHistories;
        this.screenHistories = screenHistories;
        this.ids = ids;
    }

    @Transactional(readOnly = true)
    public List<MarkerView> read(FrdScreen screen) {
        return markers.selectByScreenId(screen.id()).stream().map(FrdScreenMarkerService::viewOf).toList();
    }

    @Transactional(readOnly = true)
    public List<MarkerView> readHistory(FrdScreenHistory history) {
        return markerHistories.selectByHistoryId(history.id()).stream()
                .map(FrdScreenMarkerService::viewOf).toList();
    }

    @Transactional
    public MarkerView add(FrdScreen screen, BuilderUser author, MarkerPosition position, String description) {
        String selector = required(position.selector(), "마커를 연결할 화면 요소를 찾지 못했습니다.");
        String label = required(position.elementLabel(), "마커 위치의 화면 요소를 설명할 수 없습니다.");
        String content = description(description);
        if (selector.length() > MAX_SELECTOR_LENGTH) {
            throw new IllegalArgumentException("선택한 화면 요소의 경로가 너무 깁니다. 더 작은 영역을 선택해 주세요.");
        }
        if (label.length() > MAX_LABEL_LENGTH) label = label.substring(0, MAX_LABEL_LENGTH);
        validateRatio(position.relativeX());
        validateRatio(position.relativeY());
        validateRatio(position.documentX());
        validateRatio(position.documentY());
        Instant now = Instant.now();
        FrdScreenMarker marker = new FrdScreenMarker(ids.next(IdSequence.Kind.FRD_SCREEN_MARKER), screen.id(),
                markers.selectNextMarkerNo(screen.id()), author.accountId(), author.name(), selector, label,
                position.relativeX(), position.relativeY(), position.documentX(), position.documentY(),
                content, now, now);
        markers.insert(marker);
        syncLatestHistory(screen);
        return viewOf(marker);
    }

    @Transactional
    public MarkerView update(FrdScreen screen, String markerId, String description) {
        FrdScreenMarker marker = owned(screen, markerId);
        Instant updatedAt = Instant.now();
        String content = description(description);
        markers.updateDescription(marker.id(), content, updatedAt);
        syncLatestHistory(screen);
        return viewOf(new FrdScreenMarker(marker.id(), marker.frdScreenId(), marker.markerNo(),
                marker.authorAccountId(), marker.authorName(), marker.selector(), marker.elementLabel(),
                marker.relativeX(), marker.relativeY(), marker.documentX(), marker.documentY(),
                content, marker.createdAt(), updatedAt));
    }

    @Transactional
    public void delete(FrdScreen screen, String markerId) {
        markers.deleteById(owned(screen, markerId).id());
        syncLatestHistory(screen);
    }

    private void syncLatestHistory(FrdScreen screen) {
        FrdScreenHistory latest = screenHistories.selectLatestByScreenId(screen.id());
        if (latest == null) return;
        markerHistories.deleteByHistoryId(latest.id());
        markerHistories.snapshot(latest.id(), screen.id());
    }

    private FrdScreenMarker owned(FrdScreen screen, String markerId) {
        FrdScreenMarker marker = markers.selectById(markerId);
        if (marker == null || !marker.frdScreenId().equals(screen.id())) {
            throw new IllegalArgumentException("그런 실행 마커가 없습니다.");
        }
        return marker;
    }

    private static String description(String value) {
        String content = required(value, "마커 설명을 입력해 주세요.");
        if (content.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("마커 설명은 4,000자 이내로 입력해 주세요.");
        }
        return content;
    }

    private static String required(String value, String message) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isBlank()) throw new IllegalArgumentException(message);
        return stripped;
    }

    private static void validateRatio(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("마커 위치가 화면 범위를 벗어났습니다. 다시 선택해 주세요.");
        }
    }

    private static MarkerView viewOf(FrdScreenMarker marker) {
        return new MarkerView(marker.id(), marker.markerNo(), marker.selector(), marker.elementLabel(),
                marker.relativeX(), marker.relativeY(), marker.documentX(), marker.documentY(),
                marker.description(), marker.authorName(), marker.createdAt(), marker.updatedAt());
    }

    public record MarkerPosition(String selector, String elementLabel,
                                 Double relativeX, Double relativeY,
                                 Double documentX, Double documentY) { }

    public record MarkerView(String id, Integer markerNo, String selector, String elementLabel,
                             Double relativeX, Double relativeY, Double documentX, Double documentY,
                             String description, String authorName, Instant createdAt, Instant updatedAt) { }
}
