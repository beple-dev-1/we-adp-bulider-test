# 자율 선탐색 — 자동 트리거 규칙 및 Dispatch 템플릿

dev-interview 스킬의 자율 선탐색 단계에서 **dispatch 직전** lazy Read한다.

---

## 자동 트리거 규칙 (필수)

- **컨디션**: Phase 0-1 완료 (5항목 답변 누적) **AND** (기획서 모드일 경우 Phase 0-2 success / OCR fallback 완료).
- **타이밍**: 컨디션 만족 직후 메인의 다음 단일 응답 안에서 3 Agent 호출. 그 응답에는 다른 텍스트·질문 없이 dispatch 만 포함 (한 줄 진행 안내 허용).
- **사용자 확인 금지**: "탐색 시작할까요?" / "Y/N?" 같은 컨펌 질문 금지. dispatch 가 곧 진행 신호.
- **skip 금지**: 3 sub-agent 중 하나라도 빠뜨리면 통합 단계 결과 불완전. project.yaml 의 `db.vendor` 미설정 등 사전 미충족이면 그 agent 만 skip + 통합 단계에 "{agent} skipped — {사유}" 표기.
- **재호출 금지**: 동일 taskNumber 로 중복 dispatch 금지. 결과 미흡 시 통합 단계 §3 inline Grep 백업 또는 1:1 라운드에서 보강.

---

## 책임 분리 원칙

- **메인 (dev-interview)** = sub-agent 3개 dispatch + 결과 통합. agent 내부 playbook 모름.
- **sub-agent** = `.claude/agents/{code-investigator,db-meta-manager,security-auditor}.md` 정의에 따라 playbook 실행 (references/templates/scripts Read 포함) + 마크다운 결과 반환.

---

## Dispatch 템플릿

각 Agent 호출은 `subagent_type` 으로 직접 지정. prompt 는 입력 계약(key=value) 만 전달:

```
Agent(subagent_type="code-investigator",
      prompt="primary={대상 프로젝트} related={연동 후보 JSON} topicHints={키워드 JSON} taskNumber={N}")
Agent(subagent_type="db-meta-manager",
      prompt="topicHints={키워드 JSON} taskNumber={N}")
Agent(subagent_type="security-auditor",
      prompt="primary={대상 프로젝트} related={연동 후보 JSON} taskNumber={N}")
```

3 sub-agent: `code-investigator` · `db-meta-manager` · `security-auditor`.

---

## 금지 패턴 (anti-pattern)

> **금지**: `Skill(skill='explore-*', args='...')` 또는 `Agent(subagent_type='explore-*', ...)` — 둘 다 deprecated. 반드시 신규 직업명(`code-investigator`/`db-meta-manager`/`security-auditor`) subagent_type 사용.
> **금지**: `"Read .claude/agents/{job}.md and execute"` — agent dispatch 가 아니라 파일 Read. 격리 격실 깨짐.
> **금지**: "탐색 시작해도 될까요?" 사용자 컨펌 질문. 자율 선탐색은 자동 트리거 — 컨디션 만족 시 즉시 dispatch.
