package com.bizplay.builder.devrequest;

import com.bizplay.builder.secret.Sealed;
import com.bizplay.builder.secret.SecretSealer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발요청을 이슈로 여는 자리 설정 — 슈퍼계정이 넣는다.
 *
 * <p>⛔ <b>토큰을 되보여 주지 않는다.</b> 넣었나만 보여 주고, 바꾸려면 다시 넣는다
 * (프로젝트 접근 토큰과 같은 규칙이다).
 */
@Service
public class DevIssueTargetService {

    private final DevIssueTargetMapper targets;
    private final SecretSealer sealer;

    public DevIssueTargetService(DevIssueTargetMapper targets, SecretSealer sealer) {
        this.targets = targets;
        this.sealer = sealer;
    }

    @Transactional(readOnly = true)
    public boolean configured(String projectId) {
        return targets.selectByProjectId(projectId) != null;
    }

    @Transactional(readOnly = true)
    public DevIssueTarget of(String projectId) {
        return targets.selectByProjectId(projectId);
    }

    @Transactional
    public void save(String projectId, String baseUrl, String projectPath, String token,
                     String accountId) {
        String url = baseUrl == null ? "" : baseUrl.strip();
        String path = projectPath == null ? "" : projectPath.strip();
        String secret = token == null ? "" : token.strip();
        if (url.isBlank() || path.isBlank() || secret.isBlank()) {
            throw new IllegalArgumentException("GitLab 주소 · 저장소 · 토큰을 모두 입력해 주세요.");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("GitLab 주소는 http:// 또는 https:// 로 시작해야 합니다.");
        }
        /*
         * ⭐ 여기서 막는다 — 토큰은 HTTP 머리(PRIVATE-TOKEN)에 실린다. 한글이나 제어문자가 섞이면
         *   전송하는 순간 IllegalArgumentException 이 나고, 그 시도는 「전송중」에 굳어 사람이
         *   까닭도 모른 채 창구에 물어보게 된다. 넣는 자리에서 잡는 것이 그 사람이 고칠 수 있는 자리다.
         */
        if (!secret.chars().allMatch(one -> one >= 0x21 && one <= 0x7E)) {
            throw new IllegalArgumentException(
                    "토큰에 공백이나 한글이 섞여 있습니다. GitLab 토큰을 그대로 붙여 주세요.");
        }
        Sealed sealed = sealer.seal(secret);
        targets.upsert(new DevIssueTarget(projectId, url, path, sealed.cipher(), sealed.nonce(),
                null, accountId));
    }
}
