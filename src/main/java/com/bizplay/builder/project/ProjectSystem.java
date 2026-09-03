package com.bizplay.builder.project;

/**
 * 그 프로젝트의 시스템 하나 — 코드와, 화면에 뜨는 이름.
 * 표는 {@code builder.adk_builder_project_system} 이고 기본키는 {@code (project_id, system_code)} 둘이다.
 *
 * <p><b>코드와 이름의 정본이 다르다.</b> 코드는 기획 저장소의 {@code manifest.json} 이 정본이고
 * ({@link ProjectSystemService#syncFromRepo} 가 읽어 앉힌다) 이름은 사람이 관리 화면에서 넣는다.
 * 그래서 관리 화면의 코드 칸은 읽기 전용이다 — 사람이 지어낸 코드는 어느 화면도 못 만난다.
 *
 * <p>⚠ <b>이름이 없는 것이 정상이다.</b> 그러면 {@link #label()} 이 코드를 그대로 낸다.
 * 빈칸을 내면 「시스템이 없는 화면」으로 보이기 때문이다. 종전에 이 자리는 자바 상수 세 줄이었고
 * (백오피스·웹뷰·온라인PG) 레포의 시스템이 여섯이 되자 나머지 셋이 영문으로 떴다 —
 * ⛔ 그 상수를 되살리지 마라. 시스템 목록은 사업마다 다르다.
 */
public record ProjectSystem(String projectId, String systemCode, String displayName) {

    /** ⚠ 빈 이름은 널로 앉힌다 — 화면에서 지운 이름과 처음부터 없던 이름을 같은 것으로 다룬다. */
    public static ProjectSystem create(String projectId, String systemCode, String displayName) {
        String name = displayName == null || displayName.isBlank() ? null : displayName.strip();
        return new ProjectSystem(projectId, systemCode.strip(), name);
    }

    /** 화면에 뜨는 말. 이름이 없으면 코드다. */
    public String label() {
        return displayName == null || displayName.isBlank() ? systemCode : displayName;
    }

    public boolean named() {
        return displayName != null && !displayName.isBlank();
    }
}
