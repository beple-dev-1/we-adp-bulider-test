# 프리셋 — 스택별로 채워둔 `stack.yaml`

`stack.yaml` 의 값은 **사람이 판단하는 것이 아니라 스택이 정하는 것**이다.
Maven 프로젝트의 컴파일 명령은 어느 회사에서든 `mvn -q -DskipTests compile` 이다.
그래서 스택별로 한 벌씩 채워 두고, 프로젝트마다 다른 값만 덮어쓴다.

## 쓰는 법

`/setup` 을 실행하면 빌드 파일을 보고 알맞은 프리셋을 골라 적용한다. 그게 기본 경로다.

손으로 하려면 복사하면 된다.

```bash
cp .claude/config/presets/spring-maven.yaml .claude/config/stack.yaml
```

복사한 뒤 프로젝트마다 다른 값만 손본다 — 보통 아래 셋이다.

| 키 | 왜 다른가 |
|---|---|
| `runtime.baseUrl` | 포트가 프로젝트마다 다르다 |
| `db.*` | DB 를 쓰는지, 구조를 조회할 수단이 있는지 |
| `lint.*` | 정적 분석 도구를 붙였는지 |

## 목록

| 파일 | 대상 | 탐지 신호 | 검증 |
|---|---|---|---|
| `spring-maven.yaml` | Java · Spring Boot · Maven | `pom.xml` + `spring-boot` | 실사용 검증됨 |
| `node-npm.yaml` | TypeScript · Node · npm | `package.json` | 실사용 검증됨 |
| `spring-gradle.yaml` | Java · Spring Boot · Gradle | `build.gradle(.kts)` + `spring` | 미검증 |
| `python-pytest.yaml` | Python · pytest | `pyproject.toml` · `requirements.txt` · `setup.py` | 미검증 |
| `go.yaml` | Go | `go.mod` | 미검증 |
| `none.yaml` | 빌드·테스트 없는 저장소 | 빌드 파일 없음 | 해당 없음 |

**"미검증" 은 틀렸다는 뜻이 아니다.** 명령 형태는 표준을 따랐지만
그 프리셋으로 과업을 끝까지 돌려본 적이 없다는 뜻이다.
`/setup` 은 프리셋을 적용한 직후 아래를 돌려 실제로 실행되는지 확인한다.

```bash
bash .claude/scripts/check-profile.sh --verify-commands
```

**적어놓기만 한 명령은 첫 테스트에서 무너진다.** 그래서 적용과 확인을 붙여 둔다.

## 새 스택을 추가할 때

1. 가까운 프리셋을 복사해 파일명을 스택 이름으로 바꾼다.
2. 머리 주석에 **탐지 신호**와 **검증 상태**를 적는다. 이 둘이 없으면 `/setup` 이 고를 수 없다.
3. `--verify-commands` 로 명령이 실제로 도는지 확인한 뒤 검증 상태를 고친다.
4. 위 목록 표에 한 줄 추가한다.
5. `skills/setup/scripts/detect-stack.sh` 에 탐지 규칙을 넣는다.
   **여기까지 해야 프리셋이 살아난다** — 파일만 두면 아무도 안 쓴다.
