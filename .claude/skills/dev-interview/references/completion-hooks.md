# completion-hooks — 브리프 저장 후 후속 동작

> dev-interview 가 `{{config.outputDir}}/works/{taskId}_dev_brief.md` Write 직후 사용자에게 노출하는 안내.
> 후속 스킬(`/dev-plan` 등) 없는 조직은 `enabled: false` 또는 항목 비움.

---

## hook 1: 브리프 생성 완료 안내

**조건**: Write 성공.

**출력 템플릿**:

```
브리프 생성 완료: {outputDir}/works/{taskId}_dev_brief.md
   (본문 11섹션 + Q&A 로그 부록 {n}건)
```

`{outputDir}` 은 `.claude/config/project.yaml` 값. 브리프 파일명은 고정 `{taskId}_dev_brief.md`.

---

## hook 2: 다음 단계 제안

**조건**: `.claude/config/project.yaml` 의 `planSkill` 값에 따라 분기.

### `planSkill = dev-plan` (기본값)

```
다음 단계 (즉시 실행 가능):
- /dev-plan {taskId} — 이 브리프 기반 개발 계획서 작성
- /develop {project} — 개발 스코프 설정
- 직접 착수 — 브리프 §11 구현 순서 참고
```

### `planSkill = <other-skill>`

```
다음 단계:
- /{planSkill} {taskId} — 후속 작업
- 직접 착수 — 브리프 §11 구현 순서 참고
```

### `planSkill = ""` (후속 스킬 없음)

```
다음 단계:
- 직접 착수 — 브리프 §11 구현 순서 참고
```

---

## hook 3: 임시 파일 정리

**조건**: `tempDir/pre_exp_{taskId}/` 존재.

**출력**:

```
{tempDir}/pre_exp_{taskId}/ 임시 파일을 유지할까요?
- y: 디버깅용 (선탐색 결과 보존)
- n: 즉시 삭제
```

사용자 응답:
- `y` → 보존, `.gitignore` 에 `{{config.tempDir}}/` 포함 여부 확인 안내.
- `n` → 삭제.

---

## hook 4: gitignore 권장

**조건**: 워크스페이스 루트에 `.gitignore` 존재.

**출력** (`tempDir` 가 gitignore 에 없을 때만):

```
`.gitignore` 에 `{tempDir}/` 포함 권장 (선탐색 임시 파일).
```

---

## 조직별 커스터마이즈

이 파일 fork 시:

- `planSkill` 값에 맞춰 hook 2 텍스트 수정.
- 후속 스킬이 여러 개면 옵션 형식으로 나열 (현재 `/dev-plan` + `/develop` 처럼).
- 임시 파일 정책이 다르면 hook 3 제거 가능.
- 사내 CI 자동 트리거 등 추가 동작 필요 시 hook 5~ 신설.
