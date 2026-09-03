package com.bizplay.builder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 요구사항 분석의 설치 설정.
 *
 * <p>⛔ <b>{@link BuilderProperties} 에 합치지 마라.</b> 저쪽은 「없으면 서버가 안 뜬다」는 값들이고
 * 여기는 전부 <b>기본값이 있는</b> 값이다.
 *
 * @param allowedTools    {@code claude} 에게 허용할 도구.
 *                        <b>요구사항 분석은 기획 저장소를 읽기만 해야 한다</b> — 쓰기 도구가 열려 있으면
 *                        AI 가 남의 저장소 파일을 고칠 수 있다.
 *                        ✅ <b>2026-08-16 에 실측했다</b> — {@code --allowed-tools <tools...>} 는
 *                        실물에 있고 쉼표로 이어 붙인다. ⛔ <b>값을 여러 개 받는 꼴</b>이라
 *                        명령줄 맨 뒤에만 붙인다({@code CliClaudeRunner.command}).
 *                        비우면 플래그를 아예 안 붙이고 지시문의 금지만 남는다
 * @param model           {@code claude} 에게 쓰라고 할 모델의 <b>별칭</b>({@code sonnet}·{@code opus}).
 *                        ⛔ <b>날짜가 붙은 정식 ID 를 박지 마라</b> — 모델이 새로 나오면 낡는다.
 *                        별칭은 그 계정이 쓸 수 있는 그 등급의 최신 것을 가리킨다.
 *                        ⚠ 비우면 플래그를 아예 안 붙이고 <b>그 계정의 기본 모델</b>로 돈다 —
 *                        요금제마다 달라서 사람마다 다른 모델로 도는 상태가 된다.
 *                        그것이 2026-08-17 까지의 모습이었다
 */
@ConfigurationProperties(prefix = "builder.requirement-analysis")
public record RequirementAnalysisProperties(String allowedTools, String model,
                                            boolean codebaseMemoryEnabled,
                                            String codebaseMemoryCommand,
                                            String effort, String resumeEffort) {

    /** ⚠ 기본값을 여기서 채운다 — {@code application.yml} 에만 두면 설정을 지운 순간 null 이 된다. */
    public RequirementAnalysisProperties {
        allowedTools = allowedTools == null ? "Read,Glob,Grep" : allowedTools.strip();
        model = model == null ? "sonnet" : model.strip();
        codebaseMemoryCommand = codebaseMemoryCommand == null
                ? "codebase-memory-mcp" : codebaseMemoryCommand.strip();
        effort = effort == null || effort.isBlank() ? "medium" : effort.strip();
        resumeEffort = resumeEffort == null || resumeEffort.isBlank() ? effort : resumeEffort.strip();
    }

    /**
     * 인터뷰 한 판의 추론 수준({@code --effort}). 첫 판과 이어붙이는 판이 다를 수 있다.
     *
     * <p>⭐ <b>2026-08-26 까지는 코드에 {@code high} 가 박혀 있었다.</b> 실측(로컬 DB 인터뷰 표)에서
     * 답변 한 턴이 57~438초였고 세션 기록의 한 턴 출력이 2만~4만 토큰이었다 — 대부분 생각 토큰이다.
     * 속도가 관건이라 기본값을 {@code medium} 으로 내리고 설정으로 뺐다. 품질이 모자라면
     * {@code application.yml} 에서 {@code effort: high} 로 되돌린다.
     */
    public String effortFor(String resumeSessionId) {
        return resumeSessionId == null || resumeSessionId.isBlank() ? effort : resumeEffort;
    }

    /** 도구 제한을 걸 것인가. ⚠ 빈 글자는 「걸지 마라」의 뜻이다. */
    public boolean restrictsTools() {
        return !allowedTools.isEmpty();
    }

    /** 모델을 지목할 것인가. ⚠ 빈 글자는 「계정 기본값에 맡겨라」의 뜻이다. */
    public boolean pinsModel() {
        return !model.isEmpty();
    }

    /** 코드베이스 색인을 켰더라도 실행 파일을 비우면 MCP를 붙이지 않는다. */
    public boolean usesCodebaseMemory() {
        return codebaseMemoryEnabled && !codebaseMemoryCommand.isEmpty();
    }
}
