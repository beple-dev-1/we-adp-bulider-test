package com.bizplay.builder.frd;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 신규 화면이 IA에서 차지할 위치를 한 계약으로 저장하고 검증한다. */
@Service
public class FrdScreenIaPlacementService {

    private final FrdScreenIaPlacementMapper placements;

    public FrdScreenIaPlacementService(FrdScreenIaPlacementMapper placements) {
        this.placements = placements;
    }

    public record Request(String placementMode, String anchorScreenId, String menuPathKey,
                          String screenKind, String source) { }

    @Transactional
    public FrdScreenIaPlacement save(String frdScreenId, Request request) {
        FrdScreenIaPlacement existing = placements.selectByScreenId(frdScreenId);
        Request value = request == null ? new Request(null, null, null, null, null) : request;
        FrdScreenIaPlacement.ScreenKind kind = FrdScreenIaPlacement.ScreenKind.from(value.screenKind());
        FrdScreenIaPlacement.PlacementMode mode = modeOf(value.placementMode());
        String anchor = clean(value.anchorScreenId());
        String menuPath = clean(value.menuPathKey());

        if (mode == FrdScreenIaPlacement.PlacementMode.OPENER
                && kind == FrdScreenIaPlacement.ScreenKind.SCREEN) {
            throw new IllegalArgumentException("여는 화면 배치는 팝업·모달에만 사용할 수 있습니다.");
        }
        if (mode == FrdScreenIaPlacement.PlacementMode.CHILD
                && kind != FrdScreenIaPlacement.ScreenKind.SCREEN) {
            throw new IllegalArgumentException("팝업·모달은 상위화면이 아니라 여는 화면으로 연결해 주세요.");
        }
        if (mode == FrdScreenIaPlacement.PlacementMode.MENU
                && kind != FrdScreenIaPlacement.ScreenKind.SCREEN) {
            throw new IllegalArgumentException("팝업·모달은 메뉴가 아니라 여는 화면으로 연결해 주세요.");
        }
        if ((mode == FrdScreenIaPlacement.PlacementMode.CHILD
                || mode == FrdScreenIaPlacement.PlacementMode.OPENER) && anchor == null) {
            throw new IllegalArgumentException("IA 위치의 기준 화면을 선택해 주세요.");
        }
        if (mode == FrdScreenIaPlacement.PlacementMode.MENU && menuPath == null) {
            throw new IllegalArgumentException("화면을 배치할 메뉴를 선택해 주세요.");
        }

        FrdScreenIaPlacement placement = new FrdScreenIaPlacement(
                frdScreenId, mode, null, menuPath, anchor, kind,
                FrdScreenIaPlacement.Status.PROPOSED, sourceOf(value.source()),
                existing == null ? null : existing.developmentFileName(), Instant.now(), null);
        placements.upsert(placement);
        return placement;
    }

    public FrdScreenIaPlacement of(String frdScreenId) {
        return placements.selectByScreenId(frdScreenId);
    }

    public List<FrdScreenIaPlacement> all(String frdId) {
        return placements.selectByFrdId(frdId);
    }

    /** IA 기준으로 쓰던 연결이 사라졌음을 남긴다. 단순 삭제와 화면 제외를 구분하기 위해 행은 보존한다. */
    @Transactional
    public void invalidate(String frdScreenId) {
        FrdScreenIaPlacement placement = placements.selectByScreenId(frdScreenId);
        if (placement == null) return;
        placements.upsert(new FrdScreenIaPlacement(
                placement.frdScreenId(), placement.placementMode(), placement.structureId(),
                placement.menuPathKey(), placement.anchorScreenId(), placement.screenKind(),
                FrdScreenIaPlacement.Status.INVALID, placement.source(), placement.developmentFileName(),
                Instant.now(), placement.updatedBy()));
    }

    @Transactional
    public void release(String frdScreenId) {
        placements.deleteByScreenId(frdScreenId);
    }

    /** 정식 IA 위치가 정해지기 전에도 개발에서 사용할 파일명을 먼저 확보한다. */
    @Transactional
    public FrdScreenIaPlacement reserveDevelopmentFileName(
            String projectId, FrdScreen screen, FrdScreenIaPlacement placement) {
        return reserveDevelopmentFileName(projectId, screen, placement, Set.of());
    }

    /** 신규 화면의 연결 제안이 없어도 개발용 화면 ID를 한 번만 예약한다. */
    @Transactional
    public FrdScreenIaPlacement reserveDevelopmentFileName(String projectId, FrdScreen screen) {
        FrdScreenIaPlacement placement = placements.selectByScreenId(screen.id());
        if (placement == null) {
            placement = save(screen.id(), new Request(
                    "UNRESOLVED", null, null, "SCREEN", "AI"));
        }
        return reserveDevelopmentFileName(projectId, screen, placement, Set.of());
    }

    /** 기획 저장소의 기존 화면 ID까지 피해서 개발용 화면 ID를 예약한다. */
    @Transactional
    public FrdScreenIaPlacement reserveDevelopmentFileName(
            String projectId, FrdScreen screen, FrdScreenIaPlacement placement,
            Set<String> unavailableNames) {
        if (placement.developmentFileName() != null && !placement.developmentFileName().isBlank()) {
            return placement;
        }
        FrdScreenIaPlacement reserved = new FrdScreenIaPlacement(
                placement.frdScreenId(), placement.placementMode(), placement.structureId(),
                placement.menuPathKey(), placement.anchorScreenId(), placement.screenKind(),
                placement.status(), placement.source(),
                availableDevelopmentFileName(projectId, screen, placement, unavailableNames),
                Instant.now(), placement.updatedBy());
        placements.upsert(reserved);
        return reserved;
    }

    /** 검증을 마친 IA 위치와 이미 확보한 개발 파일명을 고정한다. */
    @Transactional
    public FrdScreenIaPlacement confirm(String projectId, FrdScreen screen, FrdScreenIaPlacement placement) {
        FrdScreenIaPlacement named = reserveDevelopmentFileName(projectId, screen, placement);
        FrdScreenIaPlacement confirmed = new FrdScreenIaPlacement(
                named.frdScreenId(), named.placementMode(), named.structureId(),
                named.menuPathKey(), named.anchorScreenId(), named.screenKind(),
                FrdScreenIaPlacement.Status.CONFIRMED, named.source(), named.developmentFileName(),
                Instant.now(), named.updatedBy());
        placements.upsert(confirmed);
        return confirmed;
    }

    private String availableDevelopmentFileName(String projectId, FrdScreen screen,
                                                FrdScreenIaPlacement placement,
                                                Set<String> unavailableNames) {
        String prefix = safePart(screen.systemCode(), "screen");
        String base = screen.baseScreenId();
        String stem = base != null && base.matches("^[a-z0-9][a-z0-9-]*$") && !base.startsWith("tmp-")
                ? base + "-new"
                : prefix + "-" + typePart(screen.screenType());
        String suffix = screen.id() == null ? "new" : screen.id().replaceFirst("^0+", "");
        if (suffix.isBlank()) suffix = "new";
        String candidate = trim(stem + "-" + suffix);
        int sequence = 2;
        FrdScreenIaPlacement occupied = placements.selectByDevelopmentFileName(projectId, candidate);
        while (unavailableNames.contains(candidate)
                || occupied != null && !occupied.frdScreenId().equals(placement.frdScreenId())) {
            candidate = trim(stem + "-" + suffix + "-" + sequence++);
            occupied = placements.selectByDevelopmentFileName(projectId, candidate);
        }
        return candidate;
    }

    private static String safePart(String value, String fallback) {
        if (value == null) return fallback;
        String safe = value.strip().toLowerCase().replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isBlank() ? fallback : safe;
    }

    private static String typePart(String value) {
        if (value == null) return "screen";
        return switch (value.strip().toUpperCase()) {
            case "LIST", "목록" -> "list";
            case "DETAIL", "상세" -> "detail";
            case "CREATE", "등록" -> "create";
            case "EDIT", "수정" -> "edit";
            case "GUIDE", "안내" -> "guide";
            default -> "screen";
        };
    }

    private static String trim(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120).replaceFirst("-+$", "");
    }

    private static FrdScreenIaPlacement.PlacementMode modeOf(String value) {
        if (value == null || value.isBlank()) return FrdScreenIaPlacement.PlacementMode.UNRESOLVED;
        try {
            return FrdScreenIaPlacement.PlacementMode.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("그런 IA 배치 방식이 없습니다: " + value);
        }
    }

    private static FrdScreenIaPlacement.Source sourceOf(String value) {
        if (value == null || value.isBlank()) return FrdScreenIaPlacement.Source.USER;
        try {
            return FrdScreenIaPlacement.Source.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("그런 IA 배치 출처가 없습니다: " + value);
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() || "__NONE__".equals(value) ? null : value.strip();
    }
}
