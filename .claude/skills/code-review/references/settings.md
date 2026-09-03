# code-review 기본 설정

<Default_Settings>

| 키 | 기본값 | 설명 |
|---|---|---|
| `default_target` | `unstaged` | 리뷰 대상 (unstaged / git / 경로) — SKILL.md 사용법과 동일 |
| `review_focus` | bug, security, performance, quality, test | 리뷰 집중 항목 |

</Default_Settings>

<Ignore_Files>

분석에서 제외하는 파일 패턴:

- `*.lock`
- `*.min.js`
- `*.min.css`
- `package-lock.json`
- `yarn.lock`
- `migrations/*`
- `*.generated.*`
- `**/js/lib/**`
- `**/vendor/**`

</Ignore_Files>

<Severity_Rules>

심각도 분류 패턴은 영역별 단일 출처로 분리되어 있다.

- 분류 알고리즘: [`severity-algorithm.md`](severity-algorithm.md) STEP 0~3
- 백엔드 패턴: [`severity-rules.md`](severity-rules.md) — CRITICAL / WARNING / INFO **카테고리(산문)**. 별도 번호 ID 없음
- 프론트엔드 패턴 ID: [`frontend-rules.md`](frontend-rules.md) 가 정본이다.
  **여기에 ID 예시를 적어 두지 않는다** — 규칙이 늘거나 이름이 바뀌면 없는 ID 를 가리키게 된다.
- 운영 안티패턴 ID(IC##/IW##): `config/project.yaml` 의 `customDocs.antiPatterns` 가 가리키는 문서
  (미설정이거나 `HARNESS:UNFILLED` 면 STEP 0 을 건너뛴다)
- 조직별 룰 추가/수정: 백엔드는 `severity-rules.md`, 프론트는 `frontend-rules.md` 편집

</Severity_Rules>

<Custom_Checklist>

팀 커스텀 체크리스트. 유형별 평가 이후에 `### 📝 팀 체크리스트` 섹션에서 검증한다.
diff에 해당 내용이 포함되지 않아 판단 불가한 항목은 출력하지 않는다.
1. 공통 유틸 중복 구현 여부 (기존 유틸 재사용 우선)
2. DB·외부 호출 후 오류 반환값 검사 누락 여부
3. 외부 API 호출 시 타임아웃 설정과 응답 상태·바디 검증 존재 여부
4. 에러 코드·예외 처리 방식이 프로젝트 표준을 따르는지
5. 환경별 값(URL·키)이 설정으로 분리되어 있는지 (하드코딩 금지)
6. 예외 처리 패턴 일관성 (catch 방식과 throw 방식 혼재 금지)
7. 자원 반환(커넥션·파일·스트림) 누락 여부
8. 컬렉션 반복 중 원본 수정 금지
9. 러너로 검증 가능한 순수 로직에 대응 테스트가 있는지
10. 프로젝트 고유 규칙은 `project-rules.md` 를 함께 적용

</Custom_Checklist>
