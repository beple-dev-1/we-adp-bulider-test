# we-adk-builder

**서버 1대 · 브라우저 접속.** 기획자가 FRD별 전용 워크트리에서 일한다.

기획팀 소유 GitLab 저장소를 클론해 두고, **현재 산출물의 생애를 관리한다.**
산출물 목록과 개수의 정본은 [`docs/artifacts.md`](docs/artifacts.md) 다.

## 지금 어디까지 서 있나 (2026-08-27 실측)

**돈다.** 화면 서른세 장이 떠 있고 마이그레이션 64개가 적용되며, 개발요청이 **GitLab 이슈로 실제로 나간다.**

| 구역 | 상태 |
|---|---|
| 로그인 · 비밀번호 변경 · Claude 연결 | 선다 |
| 관리 화면 (프로젝트 · 사용자) | 선다 |
| 프로젝트 고르기 · 문맥 유지 | 선다 |
| **FRD 작업** — 요구사항 입력 → AI 인터뷰 → 개발 범위 확인 → 작업대 | 선다 |
| **개발요청서** — 꾸러미 · 검증 두 층 · GitLab 이슈 발송 · 상태 동기화 | 선다 |
| IA(메뉴구조도) · 디자인가이드 · 솔루션 템플릿 | 선다 |
| 그린존 — 기능명세서 · 화면설계서 · 사용자 매뉴얼 | 착수 중 |
| 그린존 — 단위테스트 · 통합테스트 화면 | **아직.** 개발 완료분 역류 수신이 선 뒤다 |
| 개발 완료분 역류 수신 (as-is 재동기 포함) | **아직** |

⛔ **받은 문서 · 요구사항 · 요구사항정의서 · BRD 는 제품 흐름에서 제거됐다 (2026-08-20).**
목업 `01*`~`04*` 와 그 시절 설계 문서는 **이력이다.** 새 구현의 근거로 쓰지 마라.

**작업 단위는 FRD다.** `FRD 1개 = 작업 1개 = 워크트리 1개 = 한 사람`.

## 띄우기

**필요한 것** — JDK 17 · PostgreSQL

화면설계서와 사용자 매뉴얼의 새 개정판을 만들 서버에는 Playwright와 같은 버전의 Chromium도
배포 단계에서 한 번 설치한다. 앱이 기동 중 브라우저를 내려받지는 않으며, 설치하지 않아도 다른 기능과
이미 만들어진 화면설계서·매뉴얼은 열린다.

```bash
./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install --with-deps chromium"
```

⛔ `--only-shell` 을 붙이지 마라 (2026-08-28 실측). 캡처는 `chromium().launch()` 로 여는데
Playwright가 그 자리에서 찾는 것은 headless shell이 아니라 **Chrome for Testing(전체 chromium)** 이다.
셸만 깔면 앱이 기동 뒤 chromium을 내려받으러 나가고, TLS를 가로채는 사내망에서는 그것이
`SELF_SIGNED_CERT_IN_CHAIN` 으로 죽어 화면에는 「브라우저가 설치되지 않았습니다」로 뜬다.

```bash
# 1. 개인 설정 파일을 만든다 (저장소에 안 들어간다)
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
#    → 슈퍼계정 · 열쇠(openssl rand -base64 32) · data-root · DB 접속 정보를 채운다

# 2. 띄운다
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`http://localhost:8080` 으로 접속한다.

**DB** — 빌더의 표는 `we_adk` 데이터베이스의 **`builder` 스키마**에 산다.
같은 DB 의 `public` 은 이웃 저장소 `we-adk-admin` 몫이고 **우리는 그것을 읽지 않는다.**
Flyway 가 뜰 때 스키마와 표를 만든다.

⚠ **스키마·Flyway 설정을 `application-local.yml` 로 옮기지 마라.** 테스트가 물려받지 못해
「테스트만 초록」이 된다. 이유는 `application.yml` 주석에 적혀 있다.

**시험**

```bash
./mvnw test
```

## 담는 것

인증 · 그룹/프로젝트 등록 · 클론 · FRD 워크트리 관리 · 편집 UI ·
**AI 실행 (`claude -p`)** · 상태 DB · 산출물 도구

**기술** — Spring Boot · Java 17 · Thymeleaf · Spring Security · MyBatis · Flyway · PostgreSQL · Playwright Java

**100% 신규다.** v1 에서 옮겨오는 코드가 없다.

## 경계 — 어기면 재구성이 무의미해진다

- ⛔ **`g2c` 를 모른다.** 소스 시스템(Thymeleaf 원본 · `dino-*`)을 읽거나 추출하는 코드를 여기 두지 않는다.
- ⛔ **추출기를 돌리지 않는다.** 추출은 사람이 별도 Claude Code 세션에서 `we-adk-builder-extractor` 로 한다.
- ⛔ **기관과 css 폴더의 대응(`iks`·`tnj` 꼴)을 코드에 두지 않는다.** 그건 소스가 안 적어 둔
  **사업 지식**이고 정본은 클론의 `manifest.json` → `systems[].skins` 하나다.
  그 글자를 코드가 쥐는 순간 빌더가 `g2c` 를 아는 것이 된다.
- 기획 레포는 **기획팀 소유**(GitLab)다. 여기엔 클론만 온다.
- **다른 저장소의 문서 절(§)을 참조하지 않는다.** 필요하면 이 저장소 문서에 직접 적는다.

## 문서 어디 있나

| 무엇 | 어디 |
|---|---|
| 작업 지침 · 용어 · 경계 | [`CLAUDE.md`](CLAUDE.md) |
| 산출물 목록의 정본 | [`docs/artifacts.md`](docs/artifacts.md) |
| DB 구조 | [`docs/data-model.md`](docs/data-model.md) |
| 코드 이름 규칙 | [`docs/coding-conventions.md`](docs/coding-conventions.md) |
| 목업 만드는 규칙 | [`docs/mockup-conventions.md`](docs/mockup-conventions.md) |
| 화면 목업 60장 | [`docs/mockups/_README.md`](docs/mockups/_README.md) |
| FRD 워크트리 생애 | [`docs/frd-worktree-lifecycle-design.md`](docs/frd-worktree-lifecycle-design.md) |
| FRD 인터뷰·상태 | [`docs/frd-requirement-interview-design.md`](docs/frd-requirement-interview-design.md) |
| 설계 문서 26개 | `docs/superpowers/specs/` |
| 이미 정해져 다시 재지 않는 것 | [`docs/decided-facts.md`](docs/decided-facts.md) |

**설계 문서를 읽을 때** — 자바 주석의 `정본: docs/superpowers/specs/…` 가 그 클래스의 근거 문서를 가리킨다.
⚠ **머리에 「개정」 표시가 붙은 문서가 여럿이다.** 앞단(받은 문서 · 요구사항정의서 · BRD)을
설명하는 문단은 **이력이지 현재가 아니다.**

**화면의 정본은 목업이다.** 설계 문서와 어긋나면 목업이 이긴다.

## 문서 규칙

- 문서 하나에는 **질문 하나**만 둔다.
- **미결은 본문에 쌓지 않고 이슈로** 관리한다.
- 500줄을 넘으면 단순 분할이 아니라 **질문과 책임을 나눌 지점**을 먼저 찾는다.
- 폐기된 v1 결정 ID(`AR*`·`RQ*`·`IM*`·`AN*`·`D*`)나 옛 구현 계획에 새 설계를 맞추지 않는다.

---

재구성 배경은 동결 저장소 `we-adk-builder-v1` 의
`docs/superpowers/specs/2026-08-07-three-repo-rebuild-design.md` 에 있다.
**참고이지 권위가 아니다** — 그 결정들은 「저장소 하나 + 로컬 설치형 도구」 전제에서 나왔고
그 전제가 폐기됐다.
