# 업무 화면 목업

이 폴더는 WE 빌더의 산출물 흐름과 화면 구성을 검토하는 정적 목업이다.
**목업을 만들거나 고치는 규칙은 [`../mockup-conventions.md`](../mockup-conventions.md) 에 있다.**
데이터 구조의 정본은 [`../data-model.md`](../data-model.md), 업무 흐름과 IA의 정본은 `../superpowers/specs/`에 있다.

## 실행

저장소 루트에서 다음 명령을 실행한다.

```bash
python -m http.server 8099 --bind 127.0.0.1
```

브라우저에서 `http://127.0.0.1:8099/docs/mockups/05-frds.html`을 연다. `127.0.0.1` 바인딩은 저장소가 외부 네트워크에 노출되지 않게 한다.

## 화면 목록

왼쪽 메뉴는 현재 산출물 10개다. 받은 문서·요구사항·요구사항정의서·BRD 목업(`01*`~`04*`)은
2026-08-20 이전 설계 비교를 위해 파일만 보관하며 현재 제품 흐름이나 메뉴에 포함하지 않는다.

| 순서 | 파일 | 역할 |
|---|---|---|
| 00 | `00-project-management.html` | 슈퍼계정의 프로젝트 연결·상태 관리 |
| 00a | `00a-project-register.html` | GitLab 저장소 정보를 입력하는 프로젝트 등록 화면 |
| 00b | `00b-user-management.html` | 슈퍼 관리자의 Builder 사용자와 최초 설정 상태 관리 |
| 00c | `00c-user-register.html` | 기획자 정보와 임시 비밀번호를 입력하는 사용자 등록 화면 |
| 00d | `00d-project-detail.html` | 프로젝트 저장소 연결 정보와 상태를 확인하는 상세 화면 |
| 00e | `00e-project-failure-detail.html` | 저장소 받기 실패 이유를 확인하고 다시 시도하는 프로젝트 상세 화면 |
| 00f | `00f-user-detail.html` | 사용자 계정 정보와 최초 설정 상태를 확인하는 상세 화면 |
| 01 | `01-received-docs.html` | 폐기된 앞단 흐름 보관 — 받은 문서 목록 |
| 01-empty | `01-received-docs-empty.html` | 받은 문서 목록의 공통 빈 결과 표시 예시 |
| 01a | `01a-received-document-register.html` | 기본정보와 파일·직접 입력을 구성하는 문서 등록 화면 |
| 01b | `01b-received-document-edit.html` | 한 칸짜리 문서 내용·회의정보 확인과 요구사항 분석 실행 화면 |
| 01d | `01d-received-document-processing.html` | 문서 내용을 분석하는 동안 진행 상태를 안내하는 상세 화면 |
| 01d | `01d-received-document-processing.html` | 첨부파일의 문서 내용을 분석하는 동안 진행 상태를 보여주는 상세 화면 |
| 02 | `02-requirements.html` | 폐기된 앞단 흐름 보관 — 요구사항 목록 |
| 02a | `02a-requirement-detail.html` | AI 대화로 요구사항을 구체화하고 관련 화면 후보 확인 |
| 03 | `03-definitions.html` | 폐기된 앞단 흐름 보관 — 요구사항정의서 목록 |
| 03a | `03a-definition-detail.html` | 분리된 요건 하나의 조건·결과·범위를 확인하고 AI 대화로 독립성 검토 |
| 04 | `04-brd.html` | 폐기된 앞단 흐름 보관 — BRD 목록 |
| 04a | `04a-brd-detail.html` | BRD 초안의 요구사항정의서·대상 화면·메뉴구조 변경을 검토하고 작업 시작 |
| 05 | `05-frds.html` | FRD 작업 목록 — 출처·화면 진행·상태 확인 |
| 05-empty | `05-frds-empty.html` | FRD 작업 목록의 공통 빈 결과 표시 예시 |
| 05a | `05a-frd-workbench.html` | FRD 작업대 — 화면별 목업 미리보기와 만들기 |
| 05b | `05b-frd-wizard.html` | 요구사항을 넣고 AI 가 짚은 화면을 확정하는 마법사 |
| 05c | `05c-frd-detail-overview.html` | FRD 작업 상세 시안 A — 진행 개요와 다음 작업 중심 |
| 05d | `05d-frd-detail-spec.html` | FRD 작업 상세 시안 B — 기능 명세 항목 편집 중심 |
| 05e | `05e-frd-detail-flow.html` | FRD 작업 상세 시안 C — 사용자 화면 흐름 중심 |
| 05f | `05f-frd-detail-review.html` | FRD 작업 상세 시안 D — 완료 조건과 검토 의견 중심 |
| 05g | `05g-frd-detail-trace.html` | FRD 작업 상세 시안 E — 출처·규칙·화면·완료 기준 추적 중심 |
| 05h | `05h-frd-interview-start.html` | FRD AI 인터뷰 시안 — 요구사항 입력과 분석 시작 |
| 05i | `05i-frd-interview-question.html` | FRD AI 인터뷰 시안 — 채팅 안에서 변경 범위 검토와 승인 |
| 05j | `05j-frd-interview-result.html` | FRD AI 인터뷰 시안 — 인터뷰를 반영한 분석 결과 확인 |
| 05k | `05k-frd-interview-no-change.html` | FRD AI 인터뷰 시안 — 운영·외부 작업과 함께 개발 변경 범위 검토와 승인 |
| 05l | `05l-frd-interview-analyzing.html` | FRD AI 인터뷰 시안 — 분석 진행 내용과 관련 화면 후보를 함께 확인 |
| 05m | `05m-frd-interview-analyzing-focus.html` | FRD 요구사항 분석 중 새 시안 — 현재 분석 단계와 확인 결과를 중심으로 확인 |
| 05n | `05n-frd-interview-chat-focus.html` | AI 인터뷰 고도화 시안 A — 대화와 현재 질문에 집중하고 옆에서 확정 내용을 요약 |
| 05o | `05o-frd-interview-evidence.html` | AI 인터뷰 고도화 시안 B — 요구사항과 현재 운영 화면의 근거를 비교해 범위를 선택 |
| 05p | `05p-frd-interview-guided.html` | AI 인터뷰 고도화 시안 C — 완료·현재·다음 확인을 체크리스트로 안내 |
| 05q | `05q-frd-canvas.html` | FRD 작업 공간 시안 — 전체 화면 연결을 보고 AI 레이어로 새 화면·연결을 제안한 뒤 화면 상세로 이동 |
| 06 | `06-dev-requests.html` | 개발요청서의 개발 전달 상태 확인 |
| 06a | `06a-dev-request-detail.html` | 요청 요약·완료 기준·확인 필요 사항을 먼저 보고 개발 범위와 화면·화면 외 구현 요건을 순서대로 확인 |
| 07 | `07-menu-tree.html` | 시스템별 메뉴 구조 관리 |
| 07a | `07a-menu-tree-workbench.html` | 메뉴의 전체 경로와 연결 화면을 한 행에서 읽는 상세 시안 A |
| 07b | `07b-menu-tree-hierarchy.html` | Depth 1부터 하위 메뉴와 화면을 펼쳐 읽는 계층형 상세 시안 B |
| 07c | `07c-menu-tree-split.html` | 부모·자식 연결선을 선명하게 표시하는 C형 트리 시안 1 |
| 07d | `07d-menu-tree-cascade.html` | 부모 가지와 자식 범위를 중첩 상자로 묶는 C형 트리 시안 2 |
| 07e | `07e-menu-tree-groups.html` | 트리 계층과 연결 화면을 같은 행에 정렬하는 C형 트리 시안 3 |
| 08 | `08-solution-mockups.html` | 작업 목업의 기준이 되는 운영 화면의 버전·종류·메뉴구조도 연결·최초 작성일·수정일 확인 |
| 08a | `08a-solution-mockup-detail.html` | 기준 화면의 종류·적용 구분·작성일·수정일·수정자·수정 이력 확인 |
| 10 | `10-other.html` | 개발요청서별 후속 산출물 확인 |
| 12 | `12-design-guide.html` | 추출기가 만든 독립 HTML 디자인가이드를 Builder 안에서 안전하게 열어 실제 렌더 결과를 확인한다 |

### 로그인과 최초 설정

- `login-a.html`: Builder 로그인 화면
- `11-first-password.html`: 최초 로그인 1단계 비밀번호 설정
- `11b-claude-connect.html`: 최초 로그인 2단계 Claude Code 계정 연결
- `11c-project-select.html`: 로그인 뒤 준비된 프로젝트를 선택하는 화면
- `11d-project-empty.html`: 기획자 로그인 뒤 설정된 프로젝트가 없을 때 안내하는 화면

기획자와 슈퍼관리자 모두 비밀번호 설정 뒤 Claude 계정을 연결하거나 나중에 연결할 수 있다.

### 확정된 상세 구성

FRD는 요구사항 직접 입력, AI 인터뷰, 분석 결과 확인, 개발 범위 확인 순서로 진행한다. `FRD 작업하기`를
누르면 전용 워크트리를 만든 뒤 작업대로 이동한다. 수정할 화면을 선택하지 않은 FRD도 정상이다.

작업 목업 상세는 화면 전환과 넓은 목업 미리보기를 중심에 두고, 영역 선택과 설명 마커를 Claude Code 수정 맥락으로 전달한다.
검사기 통과 표시는 제품 화면에 걸어 두지 않는다. 저장할 때 조용히 돌고 안 통과할 때만 알린다.
실행 되돌리기는 표시한다. AI가 엉뚱하게 고쳤을 때 사람이 빠져나오는 유일한 문이다.
FRD 작업 화면은 미리보기 왼쪽의 세로 목록에서 선택한다. 목록만 스크롤되며, 사용자가 FRD에 화면을 추가할 수 있다. 목록 카드와 미리보기의 하단선을 맞추고, 모바일에서는 목록을 미리보기 위에 배치한다. 저장은 선택한 화면의 행동임을 알 수 있도록 미리보기 헤더에 둔다.
미리보기는 옅은 점 격자의 캔버스 안에 두며, 50%부터 150%까지 10% 단위로 축소·확대하고 100%로 초기화할 수 있다. 확대된 내용은 페이지 전체가 아니라 캔버스 안에서 스크롤한다.

개발요청서 상세는 요청 요약 다음에 완료 기준과 확인 필요 사항을 먼저 보여준다. 이어서 개발 범위, 화면별 작업, 화면 외 구현 요건, 운영 반영·제외 범위를 문서 순서로 읽으며, 데스크톱에서는 내용 바로가기로 긴 문서의 위치를 찾는다. 하단 관련 자료 영역은 두지 않고 해당 본문에서 바로 확인한다.

### 디자인가이드

디자인가이드는 기획 저장소 `design-guide/index.html`을 Builder 안에서 열어 보여 준다. 디자인의 내용은 추출기가 실제 화면·CSS 근거에서 만들며, Builder는 파일을 안전하게 표시하고 최신 기획 저장소와 연결한다.

## 핵심 규칙

- 화면 파일은 실물 CSS를 링크하고 `_shell.js`로 공통 셸을 렌더한다.
- 왼쪽 메뉴는 FRD 작업부터 시작하며 폐기된 앞단 네 종을 표시하지 않는다.
- 한 HTML에는 한 제품 상태만 보인다. 빈 상태나 오류 상태의 다른 시나리오는 별도 파일로 만든다.
- 목록, 등록, 상세, 작업대를 한 화면에 섞지 않는다.
- 설명은 짧게, 상태는 구체적으로, 조작은 결과가 예상되는 이름으로 쓴다.
- 상태는 색만으로 전달하지 않는다.
- 검색·필터는 데이터 규모와 판단에 필요할 때 제공한다.
- 상세한 작업 규칙은 저장소 루트의 [`AGENTS.md`](../../AGENTS.md)를 따른다.

## 이번 재설계에서 확정한 것

- 요구사항은 FRD 만들기 화면에서 직접 입력한다.
- 적용 대상은 요구사항 영역에서 복수 선택한다.
- 수정할 솔루션 목업 화면 선택은 선택 사항이다.
- AI 인터뷰는 필요한 내용을 한 번에 하나씩 묻고 기본 5문 이내에 끝낸다. 사용자는 언제든 현재 내용으로 범위를 정리할 수 있다.
- 분석 결과를 확정하면 `개발 범위 확인`으로 이동한다.
- `FRD 작업하기`가 워크트리 생성 시점이며, 성공 후에만 `수정 중`으로 바뀐다.

## 아직 만들지 않은 화면

- 기능명세서·화면설계서·테스트·사용자 매뉴얼의 개별 상세 화면
- 개발 전송 관리 화면
- 다중 문서 등록과 부분 성공 처리
- 확정된 파일 형식·용량·보안 검사 안내

## 검증

```bash
python docs/mockups/check_mockups.py
```

자동 검사 후 모든 제품 화면을 데스크톱과 375px 너비로 렌더해 페이지 전체 가로 넘침, 주요 행동 가시성, 표와 폼의 읽기 순서를 확인한다.
