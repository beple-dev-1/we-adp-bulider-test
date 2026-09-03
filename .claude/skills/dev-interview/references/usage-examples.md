# dev-interview 사용 예시

> 데모용 — SKILL.md 본문 미참조. 필요 시 사용자에게 보여줄 수 있다.

---

## 예시 1: 기획서 파일 모드

```
사용자: /dev-interview c:/docs/기획서.pdf

Claude: 기획서 파일 확인: 기획서.pdf
        파싱 전 기본 정보를 1개씩 받겠습니다.
        **개발 대상 프로젝트**가 어디인가요?
        (config.projects[] 후보: {{config.projects[].name}} — project.yaml 인벤토리 전체)

사용자: {primaryProject}
... (Phase 0-1 4항목 1:1 진행)

Claude: (Phase 0-2 — 기획서 직접 Read. `.md`·`.txt` 가 아니면 텍스트 추출본 요청)
        (자율 선탐색 — 3 sub-agent 병렬 Agent dispatch: code-investigator·db-meta-manager·security-auditor)

        라운드 1/n — 진행률: 0/n — 강도: STRONG — 근거: ...
        질문: ...
        (A) / (B) / (C)
        추천: (A) — ...

사용자: A
... (라운드 누적, 모든 갭 해소)

Claude: ## 인터뷰 결과 — 1234 ...
        [11섹션 브리프 본문]
        ## 인터뷰 Q&A 로그 (부록)
        [라운드 메타 표]
        이 내용으로 저장할까요?

사용자: OK
Claude: (Write {{config.outputDir}}/works/{taskId}_dev_brief.md  → {{config.outputDir}}/works/1234_dev_brief.md)
        브리프 생성 완료. 다음 단계: /{{config.planSkill}} 1234
```

---

## 예시 2: 주제 모드

```
사용자: /dev-interview 경조사 지급 처리
Claude: 주제로 인터뷰 시작. **개발 대상 프로젝트**가 어디인가요?
... (Phase 0-1 1~4) → (자율 선탐색 3 subagent) → 1:1 → 11섹션 + 부록 → 저장
```

---

## 예시 3: 대화형 모드

```
사용자: /dev-interview
Claude: 개발 인터뷰 시작. **개발 대상 프로젝트**가 어디인가요?
... (Phase 0-1 1~5) → 분기 → 자율 선탐색 → 1:1 → 11섹션 + 부록 → 저장
```
