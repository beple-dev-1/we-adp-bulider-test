package com.bizplay.builder.git;

import com.bizplay.builder.config.BuilderProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

@Component
public class RepoProbe {

    private final GitCommand git;
    private final Path probeDir;

    public RepoProbe(GitCommand git, BuilderProperties properties) {
        this.git = git;
        this.probeDir = properties.dataRoot().resolve("probe");
    }

    /**
     * `git ls-remote` 한 번. 파일을 하나도 안 받고 URL·토큰·기본 브랜치를 다 확인한다.
     */
    public ProbeResult probe(String repoUrl, String branch, String token) {
        try {
            GitResult result = git.run(probeDir, Duration.ofSeconds(20),
                    "ls-remote", "--heads", git.authenticatedUrl(repoUrl, token), branch);
            if (!result.succeeded()) {
                return new ProbeResult(false, "저장소에 연결하지 못했습니다. 주소 또는 접근 토큰을 확인해 주세요.");
            }
            if (result.stdout().isBlank()) {
                return new ProbeResult(false,
                        "저장소에 연결했지만 지정한 브랜치를 찾지 못했습니다. 브랜치 이름을 확인해 주세요.");
            }
            return new ProbeResult(true, null);
        } catch (GitException e) {
            return new ProbeResult(false, "저장소 확인 중 오류가 발생했습니다. " + e.getMessage());
        }
    }

    public record ProbeResult(boolean ok, String reason) {
    }
}
