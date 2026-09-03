#!/usr/bin/env bash
# 워크플로 산출물 순서 검사
#
# 테스트 결과서가 있는데 계획서가 없는 것처럼, 순서가 어긋난 산출물을 찾는다.
# 산출물 경로는 config/project.yaml 의 outputDir 에서 읽는다.
#
# 사용:  bash .claude/scripts/check-workflow-artifacts.sh
# 종료:  0 = 어긋남 없음 / 1 = 어긋남 있음

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLAUDE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT="${HARNESS_ROOT:-$(cd "$CLAUDE_DIR/.." && pwd)}"

OUT=$(grep -E '^outputDir:' "$CLAUDE_DIR/config/project.yaml" 2>/dev/null \
      | awk -F':' '{print $2}' | awk '{print $1}' | sed 's/^"//; s/"$//')

echo "────────────────────────────────────────"
echo "워크플로 산출물 검사"

if [ -z "$OUT" ] || [ "$OUT" = "__FILL__" ]; then
  echo "SKIP  outputDir 미설정 — 프로파일을 먼저 채운다"
  echo "────────────────────────────────────────"
  exit 0
fi

BASE="$ROOT/$OUT"
if [ ! -d "$BASE" ]; then
  echo "SKIP  산출물 폴더 없음: $OUT (아직 과업 산출물이 없다)"
  echo "────────────────────────────────────────"
  exit 0
fi

FAIL=0
WARN=0   # 판정을 뒤집지 않는 신호. 커밋=배포에서 트랙2 가 커밋 뒤로 밀린 경우 등

# 테스트 결과서 → 계획서 존재 확인
if [ -d "$BASE/test-reports" ]; then
  for r in "$BASE"/test-reports/*_test_result.md; do
    [ -f "$r" ] || continue
    task=$(basename "$r" | sed 's/_test_result\.md$//')
    if [ -d "$BASE/plans/$task" ]; then
      printf 'PASS  %s  결과서 ← 계획서 있음\n' "$task"
    else
      printf 'FAIL  %s  결과서만 있고 계획서(plans/%s/)가 없다\n' "$task" "$task"
      FAIL=$((FAIL+1))
    fi
  done
fi

# 테스트 결과서 → 트랙2(실행 환경 검증) 실제 수행 여부 (R15)
#
# 왜 있나: 트랙1(러너)이 GREEN 이어도 화면이 죽어 있을 수 있다.
#   2026-09-01 실측 — 러너 50건 GREEN 인 상태에서 SPA 딥링크가 전부 500 이었다.
#   `testing.md` DoD 가 "트랙2 완료 전에는 과업 미완료" 라고 쓰는데,
#   그것을 확인하는 게이트가 없어 "미실행" 이라 적힌 결과서도 그냥 통과했다.
# 무엇을 보나: 결과서 본문에 미실행·이월 표기가 남아 있는지. 존재가 아니라 내용을 본다.
# 안 쓰는 프로젝트: stack.yaml 의 runtime.enabled: false 면 건너뛴다.
RUNTIME_ON=$(awk '
  /^runtime:/   { inb=1; next }
  /^[A-Za-z]/   { inb=0 }
  inb && /^[[:space:]]+enabled:/ {
    sub(/^[[:space:]]*enabled:[[:space:]]*/, ""); sub(/[[:space:]]#.*$/, "")
    gsub(/^[[:space:]]+|[[:space:]]+$/, ""); print; exit
  }
' "$CLAUDE_DIR/config/stack.yaml" 2>/dev/null)

# 커밋=배포 프로젝트는 트랙2 가 **커밋 뒤**에 온다(rules/testing.md 「트랙2 는 언제 하는가」).
# 그 사실을 모르면 이 검사가 양쪽으로 다 깨진다 (2026-09-01 실측):
#   - 정직하게 "미실행" 이라 적으면 FAIL → 커밋해야 트랙2 를 하는데 커밋 전 게이트가 막는다(순환).
#   - 금지어를 피해 적으면 PASS → 커밋 뒤에 트랙2 를 안 돌려도 영원히 통과한다.
# 결과서가 쓰는 표식. **리터럴 상수로 둔다** — 정규식 안에만 있으면
# skill-trigger-phrases 검사가 코드가 아니라 주석을 지키게 된다(2026-09-02 실측).
# 생산 쪽 정본 — rules/testing.md DoD · skills/qa-test/SKILL.md 결과서 형식.
T2_MARK='- 트랙2:'
T2_UNVERIFIED_MARK='- 미검증:'

COMMIT_DEPLOYS=$(awk '
  /^vcs:/       { inb=1; next }
  /^[A-Za-z]/   { inb=0 }
  inb && /^[[:space:]]+commitDeploys:/ {
    sub(/^[[:space:]]*commitDeploys:[[:space:]]*/, ""); sub(/[[:space:]]#.*$/, "")
    gsub(/^[[:space:]]+|[[:space:]]+$/, ""); print; exit
  }
' "$CLAUDE_DIR/config/stack.yaml" 2>/dev/null)

# 렌더 미검증 사유가 참인지 대조하는 데 쓴다 — 브라우저를 안 쓰는 프로젝트가
# "브라우저 도구가 없다" 를 사유로 대면 그것은 거짓 사유다.
BROWSER_ON=$(awk '
  /^runtime:/   { inb=1; next }
  /^[A-Za-z]/   { inb=0 }
  inb && /^[[:space:]]+browser:/ {
    sub(/^[[:space:]]*browser:[[:space:]]*/, ""); sub(/[[:space:]]#.*$/, "")
    gsub(/^[[:space:]]+|[[:space:]]+$/, ""); print; exit
  }
' "$CLAUDE_DIR/config/stack.yaml" 2>/dev/null)

if [ -d "$BASE/test-reports" ] && [ "$RUNTIME_ON" != "false" ]; then
  for r in "$BASE"/test-reports/*_test_result.md; do
    [ -f "$r" ] || continue
    task=$(basename "$r" | sed 's/_test_result\.md$//')
    # 판정은 **요약줄 하나**로만 한다.
    #   요약줄(위 T2_MARK)에 미실행·미완 표기가 있으면 FAIL.
    # TC 표의 결과칸(`| 미실행 |` 등)은 보지 않는다 — testing.md 「건너뛴 항목 처리」가
    # "건너뛴 사실을 결과서에 남긴다" 를 요구하므로, TC 단위 미실행 표기는 **정상 기록**이다.
    # 그것을 FAIL 로 잡으면 시킨 대로 적은 결과서가 영구 FAIL 이 되고 우회 수단이 없다
    # (2026-09-01 실측 — 트랙2 를 다 끝낸 결과서가 TC 한 줄 때문에 FAIL 났다).
    T2LINE="$(grep -m1 -- "^$T2_MARK" "$r" 2>/dev/null)"
    # 금지어 목록에 기대지 않는다 — 문구를 조금만 바꾸면 빠져나간다(2026-09-01 실측).
    # `{완료}/{전체}` 를 먼저 파싱하고, 그것이 없을 때만 낱말을 본다.
    T2DONE="$(printf '%s' "$T2LINE" | sed -n 's|.*:[^0-9]*\([0-9][0-9]*\)[[:space:]]*/[[:space:]]*\([0-9][0-9]*\).*|\1 \2|p')"
    T2UNRUN=0
    # 숫자 검사와 낱말 검사를 **둘 다** 태운다. elif 로 두면 숫자가 있는 줄에서
    # 낱말 검사가 선점당해 `5/8 — 나머지 미실행` 같은 부분 완료가 통과한다(2026-09-01 실측).
    if [ -n "$T2DONE" ]; then
      T2A="${T2DONE% *}"; T2B="${T2DONE#* }"
      # 완료 < 전체 면 미완이다. 0/N 만 보면 부분 완료를 놓친다.
      [ "$T2A" != "$T2B" ] && T2UNRUN=1
    fi
    if printf '%s' "$T2LINE" | grep -qE '(미실행|미완|안 함|건너뜀|예정|못 돌|못돌|보류)'; then
      T2UNRUN=1
    fi
    # 검증 수단이 아예 없어 조건부로 닫은 경우 — rules/testing.md DoD 「예외」 절이 정본이다.
    # 그 절이 시킨 대로 적은 결과서가 FAIL 나면 지시를 따를수록 막힌다(2026-09-02 실측).
    # 다만 통과로도 내지 않는다 — WARN 으로 남겨 이월된 검증이 보이게 한다.
    # 면제에는 **자격 조건**이 있다. 표식만으로 면제하면 예외가 우회로가 된다
    # (2026-09-02 실측 — 0/5 · 빈 사유 · TC 표 안의 표식이 전부 통과했다).
    T2EXCUSED=0
    # ① 요약줄 앵커. 판정은 요약줄 하나로만 한다는 이 함수의 원칙을 면제에도 똑같이 적용한다.
    #    파일 전체를 보면 TC 표 결과칸의 `보류` 같은 낱말이 면제 근거가 된다 —
    #    그것은 FAIL 신호로는 일부러 안 보기로 한 것이라 방향이 뒤집힌다.
    T2ULINE="$(grep -m1 -- "^$T2_UNVERIFIED_MARK" "$r" 2>/dev/null)"
    # ② 표식 뒤에 사유가 있어야 한다. 빈 표식은 근거가 0이다.
    T2UREASON="$(printf '%s' "$T2ULINE" | sed "s|^$T2_UNVERIFIED_MARK||" | tr -d '[:space:]')"
    if [ -n "$T2ULINE" ] && [ -n "$T2UREASON" ]; then
      T2EXCUSED=1
      # ③ 하나도 안 했으면 면제하지 않는다. 예외 절은 "갈 수 있는 데까지 간다" 를 전제로 한다.
      #    0/N 은 아무 데도 안 간 것이라 "수단이 없어 못 했다" 와 "그냥 안 했다" 가 구분되지 않는다.
      #    검증 대상이 아예 없는 경우는 `0/0` 또는 `해당 없음` 으로 적는다 — 그 둘은 이미 통과한다.
      if [ -n "$T2DONE" ] && [ "$T2A" = "0" ] && [ "$T2B" != "0" ]; then
        T2EXCUSED=0
      fi
      # 사유가 렌더·브라우저인데 애초에 브라우저를 안 쓰는 프로젝트면 거짓 사유다.
      # ④ 사유가 렌더·브라우저인데 애초에 브라우저를 안 쓰는 프로젝트면 거짓 사유다.
      if [ "$BROWSER_ON" = "false" ] && printf '%s' "$T2ULINE" | grep -qE '렌더|브라우저|화면'; then
        T2EXCUSED=0
      fi
    fi
    if [ "$T2UNRUN" = "1" ] && [ "$T2EXCUSED" = "1" ]; then
      printf 'WARN  %s  트랙2 일부가 미검증이다 (수단 없음 — 조건부 완료)
' "$task"
      printf '        %s
' "$T2ULINE"
      echo '        이월된 검증 항목이다. HANDOFF.md 다음 단계에 남아 있는지 확인한다.'
      WARN=$((WARN+1))
    elif [ "$T2UNRUN" = "1" ] && [ "$COMMIT_DEPLOYS" = "true" ]; then
      # 커밋=배포에서는 커밋 전 트랙2 미실행이 정상 상태다. 막으면 순환에 갇힌다.
      # 다만 통과로 넘기지도 않는다 — 커밋 뒤에 반드시 돌아와야 한다.
      printf 'WARN  %s  트랙2 가 아직 남아 있다 (커밋=배포 — 커밋 뒤에 돌린다)
' "$task"
      printf '        %s
' "$T2LINE"
      WARN=$((WARN+1))
    elif [ "$T2UNRUN" = "1" ]; then
      printf 'FAIL  %s  요약줄이 트랙2 미실행·미완이라고 적혀 있다
' "$task"
      printf '        %s
' "$T2LINE"
      FAIL=$((FAIL+1))
    elif grep -qE '트랙2|실행 환경 검증' "$r"; then
      printf 'PASS  %s  트랙2 수행 기록 있음
' "$task"
    else
      printf 'FAIL  %s  결과서에 트랙2(실행 환경 검증) 절이 아예 없다
' "$task"
      echo '        러너만 돌린 결과서다. 안 한 것과 기록을 뺀 것이 구분되지 않는다.'
      FAIL=$((FAIL+1))
    fi
  done
elif [ "$RUNTIME_ON" = "false" ]; then
  printf 'SKIP  트랙2 검사 — runtime.enabled: false (실행 환경 검증을 안 쓰기로 한 프로젝트)
'
fi

# 계획서 → 브리프 존재 확인
if [ -d "$BASE/plans" ]; then
  for d in "$BASE"/plans/*/; do
    [ -d "$d" ] || continue
    task=$(basename "$d")
    if [ -f "$BASE/works/${task}_dev_brief.md" ]; then
      printf 'PASS  %s  계획서 ← 브리프 있음\n' "$task"
    else
      printf 'WARN  %s  계획서가 있으나 브리프(works/%s_dev_brief.md)가 없다\n' "$task" "$task"
      WARN=$((WARN+1))
    fi
  done
fi

echo "────────────────────────────────────────"
printf '어긋남 %d건 · 경고 %d건
' "$FAIL" "$WARN"
[ "$WARN" -gt 0 ] && echo '  경고는 판정을 뒤집지 않는다. 커밋=배포 프로젝트의 트랙2 는 커밋 뒤에 돌린다.'
echo "────────────────────────────────────────"
if [ "$FAIL" -eq 0 ]; then
  echo "RESULT: PASS"
  exit 0
fi
echo "RESULT: FAIL"
exit 1
