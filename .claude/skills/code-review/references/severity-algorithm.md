# 심각도 분류 알고리즘

> code-reviewer 에이전트가 적용하는 4-step 심각도 분류 절차 (STEP 0~3). code-reviewer 에이전트 시작 시 Read.

<Algorithm>

아래 **4-step 심각도 분류 알고리즘**을 반드시 순서대로 적용한다:

## STEP 0: 운영 안티패턴 IC/IW 매칭 (최우선)

**대조 대상은 `config/project.yaml` 의 `customDocs.antiPatterns` 가 가리키는 문서다.**
파일명을 여기에 박아 두지 않는다 — 프로젝트마다 경로가 다르고, 박아 두면 없는 파일을 가리키게 된다.

```bash
grep -A3 'customDocs:' .claude/config/project.yaml | grep antiPatterns
```

- 경로가 비어 있거나, 그 파일에 `HARNESS:UNFILLED` 표식이 남아 있으면 **STEP 0 을 건너뛴다.**
  건너뛴 사실을 리뷰 결과에 한 줄 남긴다 — 적용 못 한 검사를 적용한 것처럼 두지 않는다.
- 그 문서의 IC/IW 패턴(*패턴*·*탐지* 필드)과 발견된 이슈를 대조한다.

- **IC 매칭** → **Critical 확정** (STEP 1·2 건너뜀, **STEP 3 교차 검증도 면제** — 이미 운영 사례로 검증된 안티패턴). 패턴 ID = `[ICnn]` + 유래 사례 ID 표기 (예: `[IC01] ... — {유래 사례 ID}`, `[IC07] ... — 운영 경험`).
- **IW 매칭** → **Warning 확정** (STEP 1·2 건너뜀). 패턴 ID = `[IWnn]` + 유래 사례 ID 표기.
- **여러 IC/IW 동시 매칭** → 모두 표기하되, 가장 높은 심각도(IC)를 1차 표기로 채택.
- **매칭 없음** → STEP 1로 진행.

## STEP 1: severity-rules 패턴 매칭 (severity-rules.md 단일 출처)

`severity-rules.md` 의 CRITICAL/WARNING/INFO 카테고리와 발견된 이슈를 대조한다.

- **매칭 있음** → 해당 severity 확정. **STEP 2를 건너뛴다.**
- **여러 severity에 동시 매칭** → **높은 심각도를 채택**한다 (WARNING보다 CRITICAL 우선).
- **매칭 없음** → STEP 2로 진행.

## STEP 2: 휴리스틱 판정 (패턴 매칭 없을 때만)

`[H]` 태그를 부여하고, 아래 기준으로 판정한다:

| 심각도      | 기준                                                 |
| ----------- | ---------------------------------------------------- |
| 🔴 Critical | 운영 환경에서 **즉시** 장애 발생 또는 보안 사고 직결 |
| 🟡 Warning  | 특정 조건에서 문제 가능 (성능, 유지보수, 잠재 버그)  |
| 🔵 INFO     | 기능 무관한 품질/가독성 개선                         |

## STEP 3: Critical 교차 검증 (필수 — STEP 0 IC 매칭은 면제)

STEP 1 또는 STEP 2에서 Critical로 판정된 모든 이슈에 대해 아래 질문을 검증한다:

1. "이 이슈가 운영에서 **즉시** 장애 또는 보안 사고를 유발하는가?"
2. "severity-rules의 **WARNING 또는 INFO** 패턴에 더 적합하지 않은가?"

→ 하나라도 **"아니오"** → WARNING 또는 INFO로 **하향 조정**한다.

> **STEP 0 IC 매칭은 본 교차 검증에서 면제**된다. IC는 이미 운영 장애 사례로 검증된 안티패턴이므로 *즉시 장애 직결성*이 룰 정의 자체에 보장되어 있다. 하향 조정 금지.

</Algorithm>

<Absolute_Rules>

> **`import *`, 네이밍 위반, 브레이스 스타일, Javadoc 미작성 등 코딩 컨벤션/문서화 이슈는 어떤 경우에도 Critical이 아니다.**
> 이러한 이슈는 severity-rules.md에서 Warning — 컨벤션 또는 INFO — 컨벤션으로 명시되어 있으며, 반드시 해당 심각도를 따른다.

</Absolute_Rules>

<Calibration_Examples>

자주 발생하는 **오분류 사례**와 올바른 판정. 심각도 판정 시 반드시 참조한다.

| 발견 이슈 | ❌ 잘못된 판정 | ✅ 올바른 판정 | 이유 |
| --------- | -------------- | -------------- | ---- |
| `import java.util.*` (스타 임포트) | 🔴 Critical | 🟡 **Warning** — 컨벤션 | 런타임 장애 무관 |
| 네이밍 규칙 위반 (camelCase 등) | 🔴 Critical | 🟡 **Warning** — 컨벤션 | 런타임 장애 무관 |
| K&R 브레이스 스타일 미준수 | 🔴 Critical | 🟡 **Warning** — 컨벤션 | 런타임 장애 무관 |
| Javadoc 미작성 | 🟡 Warning | 🔵 **INFO** — 컨벤션 | 기능 무관한 문서화 이슈 |
| `catch (Exception e) {}` (빈 catch) | 🔵 INFO | 🟡 **Warning** — 안정성 | 예외 삼킴으로 장애 추적 불가 |
| `console.log()` 잔재 (화면 코드) | 🟡 Warning | 🔵 **INFO** — 컨벤션 | 기능 무관한 디버그 코드 |
| AJAX 버튼 클릭 시 `disabled` 처리 / 로딩바 누락 | 🟡 Warning | 🔴 **Critical [IC03]** (STEP 0 우선) | UI 더블클릭 방어 누락 → 중복 지급 — I5 회귀 위험 |
| 외부 거래번호 중복 체크 없는 엔드포인트 | 🟡 Warning | 🔴 **Critical [IC01]** (STEP 0 우선) | 멱등성 미적용 → 외부 재시도 시 중복 처리 — {유래 사례 ID} |
| 사용자 입력 기반 날짜 범위 쿼리 검증 없음 | 🟡 Warning | 🔴 **Critical [IC06]** (STEP 0 우선) | 무제한 범위 → DoS / DB 부하 — 운영 경험 |
| DB 커넥션 보유 중 외부 API 호출 | 🟡 Warning | 🔴 **Critical [IC07]** (STEP 0 우선) | 커넥션 미반환 상태로 외부 호출 → 응답 지연 시 커넥션풀 고갈 |

</Calibration_Examples>
