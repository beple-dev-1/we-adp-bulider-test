---
name: code-review
description: 커밋 전 변경 코드를 CRITICAL/WARNING/INFO 심각도로 검토한다. 사용자가 "코드리뷰", "리뷰해줘", "/code-review" 를 말하거나 커밋 직전이면 이 스킬을 사용한다.
---

# /code-review 스킬

커밋 전 변경 코드를 심각도 기준으로 검토한다.

## 사용법
```
/code-review [unstaged|git|경로]
```

기본값: 커밋되지 않은 변경 전체 (출력 언어: 한국어)
- `unstaged` : 아직 커밋·스테이징 되지 않은 변경 (기본값)
- `git` : `git diff --staged` 기준 (하네스 파일 변경)
- `경로` : 특정 파일·디렉토리만 대상

## 0단계: 프로파일 확인

```bash
bash .claude/scripts/check-profile.sh
```

- 종료 코드 `3` — **미연동**. 여기서 멈추고 출력 내용을 사용자에게 그대로 전달한다.
- 종료 코드 `0` — 다음 단계로 간다.

---

## 절차

### 1단계: 설정 확인
- **분할 임계는 `config/stack.yaml` 의 `review.maxFiles`·`review.maxLines` 가 정본이다.** 그 값을 읽는다.
  이 문서에 숫자를 적어 두지 않는다 — 설정을 바꿔도 문서가 안 따라와 실제와 어긋난다.
- ⚠️ 같은 블록의 **`reviewerAgent` 는 `dev-interview` Stage 2 전용이며 이 스킬과 무관하다.** 이 값이 `none` 이라고
  5단계 code-reviewer 에이전트를 생략해도 된다는 뜻이 **아니다**. 소유 주체는 `dev-interview/SKILL.md` §Stage 2 ·
  `references/reviewer-output-contract.md` 다. (dev-interview 전용 계약이며 code-review 와 혼동하지 않는다)
- 현재 `/develop` 스코프가 활성화된 경우 해당 허용 경로를 우선 대상으로 삼는다.

### 2단계: diff 수집
- **변경 수집**: `config/stack.yaml` 의 `vcs.kind` 에 맞는 diff 명령을 쓴다 (git → `git diff` / svn → `svn diff`). 실행 파일이 PATH 에 없을 수 있으므로 실패 시 전체 경로로 재시도한다.
- **하네스 파일** (`.claude/`, `CLAUDE.md`): `git diff --staged` 를 사용한다.
- 변경 파일 목록 확인: `git status -uall` 또는 `svn status`
- 🔴 **신규(추적되지 않은) 파일은 `git diff` 에 나오지 않는다.** diff 만 보면 리뷰 대상에서 통째로 빠진다.
  **신규 과업일수록 신규 파일 비율이 높다** — 빈 코드베이스에서 시작하면 거의 전부가 신규다.
  즉 첫 과업에서 리뷰가 사실상 비어 버린다 (2026-09-01 실측 — 대상 23개 중 21개가 신규였다).

  ```bash
  git status --porcelain -uall     # 신규 포함 전체 목록
  ```

  신규 파일은 **diff 가 아니라 파일 전문을 읽어** 리뷰한다. 에이전트에 넘길 때
  "이 파일들은 신규라 diff 가 없으니 직접 읽어라" 를 프롬프트에 적는다.
- **규모 판정에서 아래를 뺀다.** 안 빼면 하네스·산출물이 규모를 부풀려 불필요하게 분할 검토로 넘어간다.

  | 빼는 것 | 이유 |
  |---|---|
  | `.claude/**` · `CLAUDE.md` | 하네스 파일이다. 위에서 이미 경로를 나눠 다룬다 |
  | `project.yaml` 의 `outputDir` (기본 `.work/`) | 산출물 문서다. 리뷰 대상이 아니다 |

  (2026-09-01 실측 — 하네스를 레포에 넣는 구성에서 `111 files / 16,462 insertions` 로 잡혔다.
  실제 과업 코드는 23파일 · 2,176줄이었다.)
- `review.maxFiles` 또는 `review.maxLines` 를 넘으면 주 언어 소스를 우선 분석한다 — 단 **드롭 없이 전량 리뷰**한다.
  대용량 배치 분할·우선순위 규격은 `references/large-diff-policy.md` 참조.
- 변경사항이 없으면 안내 후 중단한다.

### 3단계: 기준 로드
다음 파일을 읽는다:
- `config/project.yaml` 의 `customDocs.antiPatterns` (설정돼 있을 때만)
- **`config/project-meta.yaml` 의 `securityKeywords`** — 이 프로젝트의 **개인정보·민감 어휘**다.
  값이 있으면 그 낱말들을 5단계 `code-reviewer` 프롬프트에 **"이 프로젝트의 개인정보 어휘"** 로 실어 보낸다.

  ```bash
  sed -n '/^securityKeywords:/,/^[A-Za-z]/p' .claude/config/project-meta.yaml     | sed -n 's/^[[:space:]]*-[[:space:]]*//p' | tr -d '"\r' | grep -v '^$'
  ```

  > **코어 semgrep 규칙에 프로젝트 낱말을 넣지 않는다.** `semgrep-rules/` 는 코어 소유라
  > 갱신에 덮이고, 도메인 어휘가 코어에 쌓이면 다른 스택 프로젝트로 전파된다.
  > 코어 정규식은 도메인 중립 낱말(`ssn`·`card_no` 등)만 유지하고 프로젝트 어휘는 이 슬롯으로 받는다.
  >
  > 2026-09-01 실측 — 예약자명(`booker`)이 코어 정규식에 없어, 감사 로그에 그것을 심어도
  > semgrep 이 `results: 0` 을 냈다. **스캔이 깨끗한 것과 구분이 안 됐다.**
  > `lint.enabled: false` 인 프로젝트에서는 PII 회귀를 커밋 전에 잡을 수단이 사람 리뷰뿐이 된다.
- `.claude/skills/code-review/references/severity-rules.md`
- `.claude/skills/code-review/references/project-rules.md`

### 4단계: Semgrep 보안 스캔

**대상은 변경된 소스 파일 전체다.** 특정 언어로 한정하지 않는다 —
한 언어만 넘기면 다른 스택 프로젝트에서 스캔 대상이 0건이 되고,
semgrep 은 종료 코드 0 을 내므로 그것이 "깨끗함" 으로 오독된다(아래 경고와 같은 사고다).

```bash
# 1순위: 프로젝트 전용 로컬 규칙 (네트워크 불필요, 항상 동작)
semgrep --config ".claude/semgrep-rules/project.yaml" \
  --quiet --json {변경된_소스_파일_목록}

# 2순위: 원격 규칙 (네트워크 가용 시 추가 실행)
#   p/secrets 는 언어 무관이다. 언어별 규칙셋은 이 프로젝트 언어에 맞는 것을 고른다
#   (stack.yaml 의 build.tool·test.framework 이 단서다 — p/java · p/python · p/javascript · p/golang 등)
semgrep --config "p/secrets" [--config "p/{이 프로젝트 언어}"] \
  --quiet --json {변경된_소스_파일_목록}
```

> 🔴 **`errors` 배열을 반드시 확인한다. `results` 만 보면 안 된다.**
> semgrep 은 **설정 파일이 없어도 종료 코드 0** 을 내고 `{"results":[], "errors":[{...}]}` 를 반환한다.
> `--quiet --json` 이라 화면에도 안 보인다. 그대로 `results` 만 읽으면
> **스캔을 안 한 것을 "0건 — 깨끗함" 으로 오독한다.** (2026-08-31 실측 — 규칙 파일명이 틀렸는데
> 종료 코드 0 · `results` 빈 배열이라 통과로 보였다.)
>
> 결과를 파일로 받아 `errors` 를 먼저 센다. 0 이 아니면 **원인을 해결하기 전까지 이 단계를 통과로 보지 않는다.**
> 규칙 파일 경로는 `config/stack.yaml` 의 `lint.securityCommand` 가 정본이다 — 그 값이 있으면 위 예시 대신 그것을 쓴다.

- Semgrep이 설치되지 않은 환경에서는 이 단계를 건너뛰고 그 사실을 결과에 명시한다.
- 원격 규칙 다운로드 실패 시(네트워크/SSL 오류) 로컬 규칙만 사용하고 계속 진행한다.
- Semgrep 탐지 항목은 code-reviewer 에이전트에 함께 전달하여 중복 판단 없이 CRITICAL로 상향 처리한다.
- 로컬 규칙 탐지 항목: 오류 검사 누락, URL·키 하드코딩, 사용자 입력 직접 출력, 개인정보 로그 노출, URL 파라미터 인코딩 누락

> semgrep 은 기본 무시 목록에 따라 **테스트 파일을 스캔하지 않는다.**
> 테스트 코드도 봐야 하면 대상 경로를 명시하거나 무시 설정을 조정한다.
>
> **빌드 산출물은 반드시 제외한다.** `config/stack.yaml` 의 `lint.excludePaths` 를 읽어
> 스캐너 제외 인자로 넘긴다 (semgrep 이면 `--exclude=경로`).
> `.semgrepignore` 파일은 **무시되는 경우가 있다**(2026-08-28 실측 — 파일을 뒀는데도
> 번들이 스캔돼 오탐 12건). 파일에 의존하지 말고 명령 인자로 준다.

### 5단계: code-reviewer 에이전트 실행
- code-reviewer 에이전트에게 diff, 기준 문서, Semgrep 스캔 결과(있는 경우)를 전달한다.
- 심각도: CRITICAL (즉시 수정) / WARNING (권고) / INFO (참고)

**Agent 도구를 쓸 수 없는 환경**(세션 정책상 금지 등)에서는 메인이 동일 기준으로 직접
리뷰하는 것을 **정식 경로로 인정**한다. 이 경로를 택하면 아래를 **추가 이행**한다.

1. **문법·컴파일 검증을 별도 단계로 수행한다.** 가능하면 실컴파일(변경 로직을 스텁과
   함께 분리해 그 언어의 컴파일러로), 불가하면 구조 검사(중괄호·괄호 균형) + 커밋 시 서버측
   컴파일 게이트 통과 확인.
2. 갈음 사실과 1번의 보강 내역을 리뷰 결과에 **1줄 명시**한다.

위 2개를 이행한 갈음은 `process-deviation` 기록 대상이 아니다.

> 근거: 메인 직접 리뷰에서 템플릿 컴파일 오류가 걸러지지 않아 커밋 후
> 발견됐다(커밋 두 건으로 뒤늦게 수정했다). 서브에이전트 리뷰와 메인 직접 리뷰는 놓치는
> 지점이 달라, 갈음 시 컴파일 검증을 별도로 세우지 않으면 같은 사고가 재발한다.

### 6단계: 결과 출력
- 화면에 마크다운 형식으로 출력한다 (파일 저장 안 함).
- CRITICAL 항목이 있으면 커밋 전 수정을 강력 권고한다.
- CRITICAL 0건이면 커밋 가능임을 안내한다.

## 프로젝트 필수 검토 항목

- **도메인 고유 항목은 `references/project-rules.md` 가 정본이다.** 읽어 함께 적용한다.
  비어 있거나 `HARNESS:UNFILLED` 면 아래 공통 항목만 적용하고, 그 사실을 결과에 한 줄 남긴다.
- 아래는 **도메인과 무관한 공통 항목**이다. 어느 프로젝트에서나 본다.

| 항목 | 무엇을 보나 |
|---|---|
| 동시성 | 여러 요청이 같은 자원을 읽고 쓰는 곳에서 갱신이 유실되지 않는가 (read-modify-write 금지) |
| 입력값 검증 | 필수값·길이·형식 검증이 누락되지 않았는가. 프로젝트의 오류 계약(브리프 §5)을 따르는가 |
| 반환값 검사 | 호출 결과를 검사하지 않고 다음 단계로 넘기지 않는가 |
| PII 로그 노출 | 개인정보·자유입력 값이 로그에 남지 않는가 |
| 하드코딩 | 환경별 URL·포트·키가 설정으로 분리돼 있는가 |
| 권한·상태 전이 | 되돌리기 어려운 흐름에 검사가 있는가 (해당하는 프로젝트만) |
