package com.bizplay.builder.project;

import com.bizplay.builder.intake.ProjectFacet;

import java.util.List;

/**
 * 상세 화면이 한 번에 받는 읽기 전용 묶음.
 *
 * <p>⚠ <b>토큰을 여기 담지 마라.</b> 화면이 보여 주는 것은 「등록됨」이라는 사실 하나뿐이고,
 * 푼 토큰이 모델에 들어가면 렌더된 HTML 에 새어 나갈 길이 생긴다.
 *
 * <p>⛔ <b>등록일시를 여기서 글자로 만들어 보낸다.</b> {@code createdAt} 은 {@code Instant} 라
 * 시간대가 없다 — 템플릿에서 {@code #temporals.format} 을 부르면 시간대를 못 정해 던진다.
 * 시간대를 정하는 것은 화면의 일이 아니다.
 */
public record ProjectDetailView(Project project,
                                List<ProjectFacet> facets,
                                List<ProjectSystem> systems,
                                String createdAtText,
                                RepositoryUpdate repositoryUpdate,
                                String repositoryUpdatedAtText) {

    public List<String> facetNames() {
        return facets.stream().map(ProjectFacet::name).toList();
    }
}
