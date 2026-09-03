# 연동 체크리스트 — 무엇을 채우면 끝인가

이 하네스는 두 종류의 파일로 이루어진다.

| 종류 | 누가 관리 | 갱신 때 |
|---|---|---|
| 코어 (`skills` · `agents` · `scripts` · `hooks` · `docs/agents`) | 하네스 | 덮어쓴다 |
| 프로파일 (`config` · `rules` · `docs/guideline` · `CLAUDE.md` · `skills/code-review/references/project-rules.md`) | **프로젝트** | 덮어쓰지 않는다 |

아래 표의 **필수** 항목을 채우면 하네스가 돈다. **권고** 항목은 비어 있어도 동작하지만,
비어 있는 만큼 AI 가 프로젝트 사정을 모르는 상태로 일한다.

---

## 필수 — 이걸 안 채우면 스킬이 멈춘다

| 파일 | 채울 것 | 완료 판정 |
|---|---|---|
| `config/project.yaml` | 워크스페이스 이름·산출물 경로·모듈 목록 | `__FILL__` 0개 |
| `config/stack.yaml` | VCS·빌드·테스트·런타임 명령 | `__FILL__` 0개 |
| `config/scope.yaml` | 수정 허용 경로·보호 경로 | `__FILL__` 0개 |

**가장 빠른 길은 `/setup` 이다.** 코드베이스를 훑어 스택을 알아내고 대부분을 자동으로 채운다.
빌드·테스트 명령을 몰라도 된다 — 그건 스택이 정하는 값이라 프리셋이 갖고 있다.

```bash
bash .claude/scripts/check-profile.sh      # 남은 개수와 위치를 알려준다
```

손으로 채운다면 스택별로 채워둔 설정을 복사하는 것이 빠르다.

```bash
cp .claude/config/presets/spring-maven.yaml .claude/config/stack.yaml
```

**어떤 프리셋이 있는지·무엇이 검증됐는지는 `.claude/config/presets/README.md` 가 정본이다.**
여기에 목록을 복사해 두지 않는다 — 프리셋을 늘리면 이 문서가 따라오지 않아 거짓이 된다.

```bash
ls .claude/config/presets/*.yaml     # 지금 있는 것
```
`config/project.yaml`·`scope.yaml` 의 채워진 예시는 `.claude/docs/examples/` 에 있다.

---

## 권고 — 비어도 돌지만, 채우면 결과가 달라진다

| 파일 | 없을 때 벌어지는 일 | 채우는 데 드는 시간 |
|---|---|---|
| `CLAUDE.md` (루트) | AI 가 프로젝트 개요를 매번 코드에서 추측한다 | 20분 |
| `rules/convention.md` | 코드 스타일이 파일마다 갈린다 | 15분 |
| `docs/guideline/backend.md` | 서버 구현이 기존 패턴을 안 따른다 | 30분 |
| `docs/guideline/frontend.md` | 화면 구현이 기존 패턴을 안 따른다 | 30분 |
| `skills/code-review/references/project-rules.md` | 리뷰가 공통 기준만 적용한다 | 20분 |
| `docs/anti-patterns.md` | 같은 사고가 반복된다 | 사고 생길 때마다 1줄 |
| `config/project-meta.yaml` | 인터뷰가 도메인 용어를 매번 되묻는다 · `securityKeywords` 가 비면 리뷰가 이 프로젝트의 민감 어휘를 모른다 | 15분 |

> **`semgrep-rules/` 는 코어다. 채우는 자리가 아니다.**
> 규칙을 여기 넣으면 갱신에 덮여 사라지고, 도메인 어휘가 코어에 쌓이면 다른 스택 프로젝트로 전파된다.
> 이 프로젝트의 민감 어휘는 위 `config/project-meta.yaml` 의 `securityKeywords` 로 받는다.

### 표식 — 어느 것인지 구분한다

| 표식 | 뜻 | 해야 할 일 |
|---|---|---|
| `HARNESS:UNFILLED` | 아직 손대지 않았다 | 채우면 결과가 달라진다 |
| `HARNESS:DEFAULTS` | **공통 기본값을 쓰는 중이다** | 없다. 이대로 둬도 정상이다 |
| `HARNESS:NA` | **이 프로젝트에 해당 없는 문서다** (화면 없는 프로젝트의 화면 지침 등) | 없다. 그대로 둔다 |

표식 종류의 정본은 `check-profile.sh` 의 `adv()` 함수다. 여기 표가 그것과 어긋나면 스크립트가 옳다.

`convention.md`·`guideline/*.md` 에는 스택과 무관한 공통 규칙이 **이미 채워져 있다**
(계층·트랜잭션·실패 처리·이름 규칙 등). 그대로 써도 되는 내용이다.
`/setup` 이 그런 문서의 표식을 `HARNESS:DEFAULTS` 로 바꿔, 정상 상태가 "미작성" 으로 보이지 않게 한다.

각 파일에는 **주석으로 된 샘플**이 들어 있다. 주석을 풀고 내용을 프로젝트 것으로 바꾼다.
샘플이 주석인 이유는 하나다 — 남의 프로젝트 규칙이 그대로 살아 있으면
리뷰가 없는 규칙을 근거로 지적하기 때문이다.

### 작성 완료 표시

각 권고 파일 맨 위에 이런 줄이 있다.

```
<!-- HARNESS:UNFILLED — 이 파일을 프로젝트 내용으로 채운 뒤 이 줄을 지운다. -->
```

- 채웠으면 **그 줄을 지운다.** 그게 작성 완료 신호다.
- `check-profile.sh` 가 이 표식을 보고 **어느 문서가 비어 있는지 목록으로** INFO 에 알려준다.
  진행을 막지는 않는다.
- 이 프로젝트에 필요 없는 문서라면, 파일을 지우거나 표식만 지우고 "해당 없음" 을 한 줄 적는다.

---

## 연동 완료라고 말할 수 있는 상태

- [ ] `check-profile.sh` 가 `[연동됨]` 을 출력한다
- [ ] `check-harness-consistency.sh` 가 **실패 0건**이다 (경고는 허용 — 아직 안 만든 경로일 수 있다)
      무엇을 검사하는지는 실행 결과의 검사 이름으로 확인한다. 여기에 목록·개수를 적어 두지 않는다
- [ ] **빌드·테스트 명령을 실제로 한 번 돌려봤다** — `check-profile.sh --verify-commands`
      (적어놓기만 한 명령은 첫 테스트에서 무너진다)
- [ ] `CLAUDE.md` 가 프로젝트 개요·워크플로를 담고 있다
- [ ] `config/scope.yaml` 의 보호 경로에 시크릿 파일이 들어 있다
- [ ] 산출물 경로(`outputDir`)가 `.gitignore` 에 있다
- [ ] `.claude/harness-friction.jsonl` 이 `.gitignore` 에 있다 (로컬 전용 마찰 로그)
- [ ] 훅을 쓸 거면 `settings.json.example` 의 `hooks` 블록을 `settings.json` 으로 옮겼다
      (안 옮기면 `/harness-review` 마찰 집계가 항상 빈다 — 마찰이 없는 것이 아니라 수집이 없는 것이다)
- [ ] 스킬 하나를 실제로 돌려봤다 (`/develop {스코프}` 로 시작 요약이 나오는지)

위 세 줄은 스크립트가 판정한다. 나머지는 사람이 확인한다.

---

## 갱신할 때

```bash
# 코어만 덮어쓴다
cp -r common-skeleton-harness/.claude/skills        .claude/
cp -r common-skeleton-harness/.claude/agents        .claude/
cp -r common-skeleton-harness/.claude/scripts       .claude/
cp -r common-skeleton-harness/.claude/hooks         .claude/
cp -r common-skeleton-harness/.claude/docs/agents   .claude/docs/
cp -r common-skeleton-harness/.claude/docs/examples .claude/docs/
cp -r common-skeleton-harness/.claude/docs/profile  .claude/docs/
cp -r common-skeleton-harness/.claude/config/presets .claude/config/
```

`config/project.yaml` · `stack.yaml` · `scope.yaml` · `rules/` · `docs/guideline/` ·
`docs/anti-patterns.md` · `CLAUDE.md` ·
**`skills/code-review/references/project-rules.md`** 는 프로젝트 소유이므로 덮어쓰지 않는다.
> ⚠ **`skills/` 를 통째로 덮어쓰면 프로젝트가 채운 리뷰 기준이 날아간다.**
> `skills/code-review/references/project-rules.md` 는 `skills/` 밑에 있지만 **프로젝트 소유**다
> (`check-profile.sh` 가 프로젝트 권고 문서로 센다). 복사 전에 빼 두고 복사 뒤에 되돌린다.
>
> ```bash
> cp .claude/skills/code-review/references/project-rules.md /tmp/pr.bak   # 복사 전
> # ... 위 cp -r 실행 ...
> cp /tmp/pr.bak .claude/skills/code-review/references/project-rules.md   # 복사 뒤
> ```
>
> 표식이 `HARNESS:UNFILLED` 인 상태(아직 안 채웠다)면 되돌릴 필요가 없다.


**`config/presets/` 는 예외로 코어 소유다** — 스택별 표준값이라 코어에서 갱신된다.
위 복사 목록에 들어 있다.
> **코어를 갱신하면 새 설정 키가 생길 수 있다.** 갱신 직후 정합성 검사를 돌리면
> `config-keys-exist` 검사가 "문서가 참조하는데 설정에 없는 키" 를 알려준다.
> 그 키를 `config/` 에 추가하면 된다.
`docs/` 를 통째로 복사하면 직접 쓴 지침이 날아간다.
