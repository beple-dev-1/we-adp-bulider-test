---
name: dev-plan
description: 개발 브리프를 받아 개발 계획서와 테스트 계획서를 작성한다. 사용자가 "계획 세워", "개발 계획서", "/dev-plan {과업번호}" 를 말하면 이 스킬을 사용한다.
---

# /dev-plan 스킬

개발 브리프를 기반으로 개발 계획서와 테스트 계획서를 작성한다.

> **오케스트레이션 원칙**: dev-planner·qa-planner 는 `Agent` 로 디스패치되는 **비대화형 서브에이전트**다 (AskUserQuestion 등 사용자 상호작용 불가). 따라서 **페이즈 분할 검토 게이트(4.5)는 메인(/dev-plan 스킬)이 중재**한다 — 서브에이전트는 분할안을 *반환만* 하고, 메인이 사용자에게 제시·승인받은 뒤 다음 단계를 재디스패치한다.
> dev-planner·qa-planner 디스패치는 기본이다. `Agent` 도구를 못 쓰는 상황이면 `.claude/rules/base-rule.md` §서브에이전트 사용 원칙에 따라 사용자에게 먼저 묻는다.

## 사용법
```
/dev-plan {과업번호 | 브리프 파일 경로}
```

서브에이전트에는 **Plan Mode 를 적용하지 않는다** —
`.claude/docs/agents/common/subagent-plan-mode-policy.md` 참조.

## 절차

### 1단계: 브리프 확인
- `{{config.outputDir}}/works/{과업번호}_dev_brief.md` 파일을 읽는다.
- 브리프가 없으면 `/dev-interview`를 먼저 실행하도록 안내한다.

### 2단계: dev-planner 디스패치 — 페이즈 분할안 산출 (split 모드)
- `Agent(subagent_type="dev-planner", prompt="mode=split brief={{config.outputDir}}/works/{과업번호}_dev_brief.md taskNumber={과업번호}")`
- dev-planner 가 절차 1~4단계(브리프 분석·스코프 역매칭·페이즈 분할)를 수행하고 **분할안(페이즈 테이블 + 도메인≥2 묶임 플래그)을 텍스트로 반환**한다. 문서 작성·사용자 질문은 하지 않는다.

### 3단계: 페이즈 분할 검토 게이트 (메인 중재) ★
- 2단계 반환 분할안을 **메인이 사용자에게 제시**한다 — 포맷·선결 검토(도메인≥2 분리)·응답 분기는 `.claude/docs/agents/dev-planner/references/gate-format.md` 를 따른다.
- **제시할 때 다음 단계의 규모를 한 줄로 예고한다.** 승인이 곧 4단계(detail) 착수이고,
  그 단계는 페이즈 수에 비례해 커진다 — 실측으로 페이즈 4개에 십수 분·문서 5개가 나왔다.
  사용자가 무엇을 승인하는지 알고 누르게 한다.
  ```
  이 분할로 진행하면 다음 단계에서 문서 {페이즈수+1}개를 만든다 (루트 1 + 페이즈 {N}).
  페이즈가 많을수록 오래 걸린다 — 수 분에서 십수 분.
  ```
- 승인 → 4단계. 수정 요청 → 2단계 재디스패치(수정 지시 전달). 도메인 분리 선택 → dev-interview 재요청 안내 후 종료.
- 분할 자명한 초소형 과업(브리프 §11 ≤ 3단계 + 페이즈 2~3개)은 게이트 생략 가능 — 생략 사실 1줄 명시.
  단 `gate-format.md` 의 **"스킵보다 우선하는 조건"**(금액·권한·상태 전이·스키마·외부 연동)에
  걸리면 생략하지 않는다.

### 4단계: dev-planner 재디스패치 — 계획서 작성 (detail 모드)
- `Agent(subagent_type="dev-planner", prompt="mode=detail taskNumber={과업번호} approvedSplit={승인된 페이즈 목록}")`
- dev-planner 가 절차 5~9단계(코드 탐색 + 루트/페이즈 문서 작성 + 자체 점검)를 수행하여 `{{config.outputDir}}/plans/{과업번호}/` 에 저장:
  - `{과업번호}_dev_plan.md` — 루트 계획서 (`.claude/docs/agents/dev-planner/references/root-doc-schema.md` 형식)
  - `phases/phase_{N}.md` — 단계별 상세 계획 (`.claude/docs/agents/dev-planner/references/phase-doc-schema.md` 8섹션 형식)

### 5단계: qa-planner 디스패치 — 테스트 계획서 작성
- `Agent(subagent_type="qa-planner", prompt="taskNumber={과업번호}")`
- 개발 계획서·브리프 기반으로 `{{config.outputDir}}/plans/{과업번호}/{과업번호}_test_plan.md` 생성 (`.claude/docs/agents/qa-planner/references/root-test-schema.md` 형식, TC 목록 + DoD).

## 계획서에 "하지 않는다" 를 쓸 때

브리프나 계획서가 **"우리는 X 를 하지 않는다"** 를 쓰면, 착수 전에 한 가지를 더 확인한다.

> **프레임워크·라이브러리가 이미 X 를 하고 있지는 않은가?**

- 안 물으면 **"안 한다" 가 "안 일어난다" 로 조용히 바뀐다.** 그 뒤 인터뷰→계획→구현이
  같은 미확인 전제를 그대로 물려받고, 심하면 코드 주석에까지 옮겨 적힌다.
- 확인했으면 계획서에 **실측 근거**를 적는다. 확인 못 했으면 **"미확인 전제"** 로 표시하고
  구현 첫 단계에서 실측하게 한다. 확인하지 않은 것을 확정 사항처럼 쓰지 않는다.
- (2026-09-01 실측 — "검색어 이스케이프는 DB 방언을 타서 비용이 크니 하지 않는다" 고 썼는데,
  Spring Data 파생 쿼리가 방언 안전하게 자동으로 처리하고 있었다. 비용 근거 자체가 없었고,
  계획서 문장이 Javadoc 에까지 옮겨져 **주석이 거짓말을 하는 상태**가 됐다.
  러너가 처음으로 그것을 깼다.)

## 완료 후
계획서 저장 경로(루트·페이즈·테스트)를 안내하고 `/develop {스코프} {과업번호}` 로 구현을 시작하도록 안내한다.
