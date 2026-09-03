#!/usr/bin/env bash
# 프로파일 연동 검사
#
# 하네스가 이 프로젝트에 연동됐는지 판정한다.
# 판정 근거는 두 가지뿐이다 — 자리표시자(__FILL__) 잔존, 필수 키 존재.
#
# 사용:  bash .claude/scripts/check-profile.sh [--quiet] [--verify-commands]
#          --quiet            한 줄로만 보고한다 (다른 스킬이 부를 때)
#          --verify-commands  stack.yaml 의 명령을 실제로 실행하고 VCS 준비 상태까지 본다
#        bash .claude/scripts/check-profile.sh --quiet   (요약 1줄만)
#
# 종료 코드
#   0  연동됨
#   3  미연동 — 채울 것이 남았다
#   1  검사 자체 실패 (설정 파일 없음 등)

set -u

QUIET=0
VERIFY=0
case "${1:-}" in
  --quiet) QUIET=1 ;;
  --verify-commands) VERIFY=1 ;;
esac

# .claude 위치 찾기 — 스크립트 위치 기준(호출 위치에 안 묶이게)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLAUDE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG_DIR="$CLAUDE_DIR/config"

# 세션이 열린 폴더와 지금 검사하는 폴더가 다르면 알린다.
# 스킬·스크립트는 내용이 같아 무해하지만 settings.json(권한·훅)은 **세션 루트 것만** 뜬다.
# 즉 보호 장치만 골라서 빠지고, 그 사실이 어디에도 안 나타난다(2026-09-01 실측).
if [ -n "${CLAUDE_PROJECT_DIR:-}" ]; then   # set -u — 기본값을 반드시 준다
  _sess="$(cd "$CLAUDE_PROJECT_DIR" 2>/dev/null && pwd)"
  _work="$(cd "$CLAUDE_DIR/.." 2>/dev/null && pwd)"
  if [ -n "$_sess" ] && [ -n "$_work" ] && [ "$_sess" != "$_work" ]; then
    echo "[주의] 세션 루트와 작업 폴더가 다르다."
    echo "       세션 루트: $_sess"
    echo "       작업 폴더: $_work"
    echo "       settings.json 의 권한·훅은 세션 루트 것이 적용된다 — 이 폴더의 것은 안 뜬다."
    echo "       마찰 로그도 세션 루트 쪽에 쌓인다. 이 폴더에서 세션을 다시 여는 편이 안전하다."
    echo ""
  fi
fi

PLACEHOLDER='__FILL__'

# ── --verify-commands — 프로파일에 적은 명령이 실제로 도는지 (F11) ──
# 적어놓기만 하고 한 번도 실행하지 않은 명령은 첫 테스트에서 무너진다.
if [ "$VERIFY" -eq 1 ]; then
  SCRIPT_DIR_V="$(cd "$(dirname "$0")" && pwd)"
  CLAUDE_V="$(cd "$SCRIPT_DIR_V/.." && pwd)"
  ROOT_V="$(cd "$CLAUDE_V/.." && pwd)"
  STACK="$CLAUDE_V/config/stack.yaml"
  cd "$ROOT_V" || exit 1

  get_val() {  # get_val <키>
    # 인라인 주석을 떼낸다. 템플릿이 `compileCommand: __FILL__   # 예: mvn ...` 형태로
    # 배포되므로, 안 떼면 주석까지 명령으로 실행된다(2026-09-01 실측 — 3개 프로젝트에서
    # 전부 재현. `""  # 예: ...` 는 빈값 가드도 빗나가 exit 127 이 났다).
    grep -E "^[[:space:]]+$1:" "$STACK" 2>/dev/null | head -1 \
      | sed "s/^[[:space:]]*$1:[[:space:]]*//" \
      | sed 's/[[:space:]]#.*$//' \
      | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
  }

  # 섹션 안의 키를 읽는다. enabled 처럼 같은 이름이 여러 블록에 있는 키에 쓴다.
  section_val() {  # section_val <섹션> <키>
    awk -v sec="$1" -v key="$2" '
      $0 ~ "^" sec ":"        { inb=1; next }
      /^[A-Za-z]/             { inb=0 }
      inb && $0 ~ "^[[:space:]]+" key ":" {
        sub("^[[:space:]]*" key ":[[:space:]]*", "")
        sub("[[:space:]]#.*$", "")
        gsub(/^[[:space:]]+|[[:space:]]+$/, "")
        print; exit
      }
    ' "$STACK" 2>/dev/null
  }

  # stack.yaml 의 env·toolPaths 를 적용한다 (명령에 PC 경로를 박지 않기 위한 자리)
  #   env:       키: 값  (주석·빈 맵 {} 은 무시)
  #   toolPaths: - 경로  (PATH 앞에 붙는다)
  ENV_LINES=$(awk '
    /^env:/            { inb=1; next }
    /^[A-Za-z]/        { inb=0 }
    inb && /^[[:space:]]+[A-Za-z_][A-Za-z0-9_]*:/ { print }
  ' "$STACK" 2>/dev/null)
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    k=$(printf '%s' "$line" | sed 's/^[[:space:]]*//; s/:.*//')
    v=$(printf '%s' "$line" | sed 's/^[[:space:]]*[^:]*:[[:space:]]*//; s/[[:space:]]*$//')
    [ -z "$k" ] && continue
    export "$k=$v"
    echo "  env  $k=$v"
  done <<EOF
$ENV_LINES
EOF

  TOOL_LINES=$(awk '
    /^toolPaths:/      { inb=1; next }
    /^[A-Za-z]/        { inb=0 }
    inb && /^[[:space:]]*-[[:space:]]/ { print }
  ' "$STACK" 2>/dev/null | sed 's/^[[:space:]]*-[[:space:]]*//; s/[[:space:]]*$//')
  while IFS= read -r tp; do
    [ -z "$tp" ] && continue
    PATH="$tp:$PATH"
    echo "  path $tp"
  done <<EOF
$TOOL_LINES
EOF
  export PATH

  echo "────────────────────────────────────────"
  echo "명령 실행 검증 (실제로 돌려본다)"
  echo "────────────────────────────────────────"

  VFAIL=0
  VCSFAIL=0   # 커밋 준비 상태 실패. 명령 검증(VFAIL)과 섞지 않는다 —
              # 섞으면 "stack.yaml 의 명령을 고쳐라" 라고 안내하는데 고칠 곳은 git config 다
  VWARN=0
  VRUN=0    # 실제로 실행한 명령 수. 0 이면 "통과" 가 아니라 "검사한 것이 없다"
  # 명령 로그를 둘 곳. 실패했을 때 사후에 볼 것이 이것뿐이라 지우지 않는다
  # (2026-09-01 실측 — 지워 버려서 1회성 실패의 원인을 끝내 못 봤다).
  # 설정 파일이 CRLF 인 경우가 흔하다 — CR 을 안 걷어내면 경로 끝에 제어문자가 붙는다
  # 값 뒤 인라인 주석을 뗀다 — get_val() 이 정확히 이 이유로 같은 파이프를 쓴다.
  # 안 떼면 "__FILL__            # 예: target/tmp" 가 통째로 경로가 되어
  # __FILL__ 가드를 빗나가고 레포 루트에 쓰레기 디렉터리가 생긴다(2026-09-01 실측).
  VLOGDIR="$(sed -n 's/^tempDir:[[:space:]]*//p' "$CONFIG_DIR/project.yaml" 2>/dev/null | sed 's/[[:space:]]*#.*$//' | tr -d '\r"' | sed 's/[[:space:]]*$//' | head -1)"
  [ -n "$VLOGDIR" ] && [ "$VLOGDIR" != "__FILL__" ] || VLOGDIR=".harness-tmp"
  mkdir -p "$VLOGDIR" 2>/dev/null || VLOGDIR="."

  # 각 명령이 어느 블록의 enabled 에 딸려 있는지 — 꺼둔 기능의 명령은 검사하지 않는다
  owner_section() {
    case "$1" in
      compileCommand|buildCommand) echo "build" ;;
      runAllCommand)               echo "test" ;;
      command)                     echo "lint" ;;
    esac
  }

  # startCommand 는 서버가 떠서 끝나지 않으므로 검사하지 않는다 (아래 안내 참조)
  for key in compileCommand buildCommand runAllCommand command; do
    label="$key"
    [ "$key" = "command" ] && label="lint.command"

    # 1) 그 기능을 안 쓰기로 했으면 명령을 실행하지 않는다.
    #    이게 없으면 "안 되는 항목은 enabled: false 로 내려라" 라는 처방이 무력해진다.
    sec="$(owner_section "$key")"
    if [ -n "$sec" ] && [ "$(section_val "$sec" enabled)" = "false" ]; then
      printf 'SKIP  %-16s %s.enabled: false — 안 쓰기로 한 기능\n' "$label" "$sec"
      continue
    fi

    cmd="$(get_val "$key")"
    # 2) "안 씀" 표기를 실행하지 않는다.
    #    하네스는 여러 곳에서 "안 쓰는 항목은 none 으로 적는다" 고 안내한다.
    #    그 값을 여기서 실행하면 지시를 따른 사람이 반드시 실패한다.
    case "$cmd" in
      ""|'""'|"''"|none|None|NONE|__FILL__)
        printf 'SKIP  %-16s 값 없음 또는 안 씀(%s)\n' "$label" "${cmd:-빈 값}"
        continue ;;
    esac
    printf '실행  %-16s %s\n' "$label" "$cmd"
    VRUN=$((VRUN+1))
    # 파이프를 쓰지 않는다 — 종료 코드가 가려진다
    VLOG="$VLOGDIR/verify-$label.log"
    if eval "$cmd" > "$VLOG" 2>&1; then
      printf 'PASS  %-16s 종료 코드 0\n' "$label"
      rm -f "$VLOG"
    else
      code=$?
      # 러너가 "테스트 파일이 없다" 로 실패한 것은 설정 문제가 아니다
      if grep -qiE 'no test (files )?found|no tests found|no test suites found|found no tests' \
           "$VLOG"; then
        printf 'WARN  %-16s 테스트 파일이 아직 없다 (명령 자체는 정상)\n' "$label"
        VWARN=$((VWARN+1))
        rm -f "$VLOG"
      else
        printf 'FAIL  %-16s 종료 코드 %s\n' "$label" "$code"
        # 마지막 몇 줄만 보면 안 된다 — Maven·Gradle 은 실패해도 늘 고정 안내문으로 끝나
        # 그 자리의 정보량이 0 이다(2026-09-01 실측). 실패 신호줄을 먼저 찾는다.
        SIG='Tests run:.*(Failures|Errors): *[1-9]|FAILED|FAIL |AssertionError|Caused by:|error TS[0-9]|SyntaxError|Traceback|npm ERR!'
        if grep -qEi "$SIG" "$VLOG"; then
          echo '        실패 신호줄:'
          grep -nEi "$SIG" "$VLOG" | head -8 | sed 's/^/          /'
        else
          echo '        로그 마지막 30줄:'
          tail -30 "$VLOG" | sed 's/^/          /'
        fi
        printf '        전체 로그: %s\n' "$VLOG"
        echo '        명령이 아니라 환경 때문일 수 있다 — 한 번 더 돌려 같은 자리에서 실패하는지 본다.'
        VFAIL=$((VFAIL+1))
      fi
    fi
  done

  # ── VCS 준비 상태 (R20) ──────────────────────────────
  # 빌드 도구가 도는지는 위에서 봤는데 VCS 는 안 봤다. 그래서 절차를 다 밟고
  # 사용자 승인까지 받은 뒤 실제 커밋에서 죽는 일이 났다(2026-09-01 실측 —
  # `Author identity unknown`). 커밋은 워크플로 마지막이라 가장 비싼 자리에서 막힌다.
  # 비개발자는 `git config user.email` 을 모른다 — 명령을 그대로 찍어 준다.
  VCS_KIND="$(section_val vcs kind)"
  case "$VCS_KIND" in
    git)
      # git config 는 저장소 밖에서도 전역값을 반환한다 — 저장소 여부를 먼저 본다.
      # 안 보면 git init 안 한 폴더에서 통과를 내고 커밋에서 죽는다.
      if ! git rev-parse --git-dir >/dev/null 2>&1; then
        printf 'FAIL  %-16s git 저장소가 아니다 — git init 이 먼저다
' "vcs-identity"
        VCSFAIL=$((VCSFAIL+1))
      else
      GIT_EMAIL="$(git config user.email 2>/dev/null)"
      GIT_NAME="$(git config user.name 2>/dev/null)"
      if [ -n "$GIT_EMAIL" ] && [ -n "$GIT_NAME" ]; then
        printf 'PASS  %-16s %s <%s>
' "vcs-identity" "$GIT_NAME" "$GIT_EMAIL"
      else
        printf 'FAIL  %-16s 커밋 작성자 정보가 없다 — 커밋이 실패한다
' "vcs-identity"
        echo '        이 저장소에만 설정하려면 아래 두 줄을 그대로 실행한다:'
        echo '          git config user.name  "내 이름"'
        echo '          git config user.email "내 메일주소"'
        echo '        (모든 프로젝트에 적용하려면 --global 을 붙인다)'
        VCSFAIL=$((VCSFAIL+1))
      fi
      fi

      # 기본 브랜치 이름이 실제와 맞는가 — 틀리면 **보호가 꺼지는 방향**으로 틀린다.
      # /git 커밋 절차 0단계가 "현재 브랜치 == defaultBranch" 일 때만 분기하므로,
      # master 를 쓰는데 main 이 적혀 있으면 조건이 거짓이 되어 분기를 안 하고
      # 기본 브랜치에 직접 커밋된다. 실패가 아니라 침묵이라 눈치챌 계기가 없다
      # (2026-08-31 실측 — 3회차 내내 그랬고 아무 게이트도 안 잡았다).
      #
      # 현재 브랜치와 비교하지 않는다 — 피처 브랜치에서 작업 중이면 다른 것이 정상이다.
      # **그 이름의 브랜치가 실재하는지**를 본다.
      DEFBR="$(section_val vcs defaultBranch)"
      case "$DEFBR" in
        ""|none|None|NONE|__FILL__) ;;
        *)
          if git show-ref --verify --quiet "refs/heads/$DEFBR"; then
            printf 'PASS  %-16s 기본 브랜치 %s 실재\n' "vcs-branch" "$DEFBR"
          else
            printf 'FAIL  %-16s stack.yaml 의 defaultBranch: %s 라는 브랜치가 없다\n' "vcs-branch" "$DEFBR"
            echo '        이 값이 틀리면 /git 이 기본 브랜치 보호를 건너뛴다 — 조용히 직접 커밋된다.'
            printf '        실제 브랜치 목록: %s\n' "$(git for-each-ref --format='%(refname:short)' refs/heads | tr '\n' ' ')"
            VCSFAIL=$((VCSFAIL+1))
          fi ;;
      esac
      ;;
    svn)
      # 자격 증명은 서버·캐시에 달려 있어 로컬에서 판정하지 않는다. 그건 그대로 둔다.
      # 다만 "여기서 svn 을 쓸 수 있기는 한가" 는 로컬에서 결정론으로 확인된다.
      # 안 보면 git 쪽 R20 과 같은 자리에서 죽는다 — 절차를 다 밟고 커밋에서 실패한다.
      if ! command -v svn >/dev/null 2>&1; then
        printf 'FAIL  %-16s svn 명령을 찾을 수 없다 — 커밋이 실패한다
' "vcs-identity"
        echo '        svn 클라이언트를 설치하거나, 설치돼 있으면 그 bin 경로를'
        echo '        stack.yaml 의 toolPaths 에 넣는다.'
        VCSFAIL=$((VCSFAIL+1))
      elif [ "$(section_val vcs separateHarnessRepo)" = "true" ]; then
        # 하네스와 소스가 다른 곳에 있다. 여기(.claude 의 부모)가 작업본이 아닐 수 있고
        # 그것이 정상이다 — 소스 쪽을 봐야 하는데 그 경로를 이 스크립트가 모른다.
        printf 'SKIP  %-16s svn — separateHarnessRepo: true 라 여기서 작업본 여부를 판정하지 않는다
' "vcs-identity"
      elif ! svn info >/dev/null 2>&1; then
        printf 'FAIL  %-16s svn 작업본이 아니다 — svn checkout 이 먼저다
' "vcs-identity"
        VCSFAIL=$((VCSFAIL+1))
      else
        printf 'SKIP  %-16s svn 작업본 확인됨 — 자격 증명은 서버·캐시에 달려 있어 여기서 판정하지 않는다
' "vcs-identity"
      fi
      ;;
    none|"")
      printf 'SKIP  %-16s vcs.kind: %s — 형상관리를 쓰지 않는다
' "vcs-identity" "${VCS_KIND:-없음}"
      ;;
    *)
      printf 'SKIP  %-16s 모르는 vcs.kind: %s
' "vcs-identity" "$VCS_KIND"
      ;;
  esac

  echo "────────────────────────────────────────"
  echo "* startCommand 는 서버가 계속 떠 있어 이 검사에서 제외한다 — 직접 한 번 띄워 확인한다."
  # 커밋 준비 실패는 명령 검증 실패와 갈라 보고한다 —
  # 섞으면 "stack.yaml 의 명령을 고쳐라" 라고 안내하는데 고칠 곳은 git config 다.
  if [ "$VCSFAIL" -ne 0 ]; then
    echo "커밋 준비 실패 ${VCSFAIL}건 — 위 vcs-identity 안내를 따른다."
  fi
  if [ "$VFAIL" -eq 0 ] && [ "$VCSFAIL" -eq 0 ] && [ "$VRUN" -eq 0 ]; then
    # 전부 SKIP 이면 "명령이 돈다" 가 아니라 "검사한 것이 없다" 다.
    # 이 둘을 같은 PASS 로 내면 아무 명령도 안 돌아본 프로젝트가 검증됐다고 읽힌다.
    echo "실행한 명령 0건 — 빌드·테스트·정적분석을 전부 안 쓰기로 한 상태다."
    echo "  이 결과는 '명령이 돈다' 는 뜻이 아니다. 검사할 대상이 없다는 뜻이다."
    echo "  나중에 빌드·테스트를 붙이면 enabled 를 올리고 이 검사를 다시 돌린다."
    echo "RESULT: PASS (검사 대상 없음)"
    exit 0
  fi
  if [ "$VFAIL" -eq 0 ] && [ "$VCSFAIL" -eq 0 ]; then
    echo "명령 검증 통과 — 실제로 실행한 명령 ${VRUN}건. (경고 ${VWARN}건)"
    echo "RESULT: PASS"
    exit 0
  fi
  if [ "$VFAIL" -eq 0 ]; then
    echo "명령은 전부 통과했다. 커밋 준비만 남았다 — 위 안내를 따르고 다시 돌린다."
    echo "RESULT: FAIL"
    exit 3
  fi
  echo "명령 검증 실패 ${VFAIL}건 — stack.yaml 의 명령·env·toolPaths 를 고친다. (경고 ${VWARN}건)"
  echo "RESULT: FAIL"
  exit 3
fi

if [ ! -d "$CONFIG_DIR" ]; then
  echo "[검사 실패] 설정 폴더가 없다: $CONFIG_DIR"
  exit 1
fi

FILES=""
for f in project.yaml stack.yaml scope.yaml; do
  if [ -f "$CONFIG_DIR/$f" ]; then
    FILES="$FILES $CONFIG_DIR/$f"
  else
    echo "[검사 실패] 필수 설정 파일이 없다: config/$f"
    exit 1
  fi
done

# ── 1. 자리표시자 잔존 ──────────────────────────────────
LEFT=0
REPORT=""
for f in $FILES; do
  base="config/$(basename "$f")"
  # 주석 줄(#로 시작)은 설명문이므로 세지 않는다
  while IFS=: read -r line _; do
    [ -z "$line" ] && continue
    raw=$(sed -n "${line}p" "$f")
    case "$(printf '%s' "$raw" | sed 's/^[[:space:]]*//' | cut -c1)" in
      '#') continue ;;
    esac
    LEFT=$((LEFT + 1))
    if printf '%s' "$raw" | grep -qE '^[[:space:]-]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:'; then
      key=$(printf '%s' "$raw" | sed 's/^[[:space:]-]*//; s/[[:space:]]*:.*//')
    else
      key="목록 항목"
    fi
    REPORT="$REPORT
  - $base:$line  ($key)"
  done <<EOF
$(grep -n "$PLACEHOLDER" "$f" 2>/dev/null)
EOF
done

# ── 2. 필수 키 존재 ─────────────────────────────────────
MISSING=""
need_key() {  # need_key <파일> <키>
  grep -qE "^[[:space:]-]*$2[[:space:]]*:" "$CONFIG_DIR/$1" 2>/dev/null \
    || MISSING="$MISSING
  - config/$1 에 '$2' 키가 없다"
}
need_key project.yaml workspaceName
need_key project.yaml outputDir
need_key project.yaml projects
need_key stack.yaml vcs
need_key stack.yaml build
need_key stack.yaml test
need_key scope.yaml scopes

# ── 3. 예시값 잔존 검사 (F4) ────────────────────────────
# 예시 프로파일을 복사만 하고 값을 안 바꾼 상태를 잡는다.
# 자리표시자만 세면 "남의 프로젝트 경로" 가 통과한다.
ROOT_DIR="$(cd "$CLAUDE_DIR/.." && pwd)"
SAMPLE=""
for token in "acme-order" "acme-web" "com/acme/order" "packages/api" "demo-service"; do
  if grep -rq "$token" "$CONFIG_DIR"/project.yaml "$CONFIG_DIR"/scope.yaml 2>/dev/null; then
    SAMPLE="$SAMPLE $token"
  fi
done

# ── 4. 모듈 경로 존재 검사 (F4) ─────────────────────────
# projects[].dir 는 "앞으로 만들 경로" 가 아니라 이미 있어야 하는 경로다.
BADDIR=""
DIRS=$(grep -E '^[[:space:]]+dir:' "$CONFIG_DIR/project.yaml" 2>/dev/null \
       | awk -F'dir:' '{print $2}' | awk '{print $1}' | sed 's/^"//; s/"$//')
for d in $DIRS; do
  case "$d" in
    __FILL__*) continue ;;
  esac
  [ -d "$ROOT_DIR/$d" ] || BADDIR="$BADDIR $d"
done

# ── 5. 권고 문서 작성 여부 (판정에 영향 없음 · INFO) ─────
# 표식을 구분한다. 섞으면 정상 상태가 "덜 된 것" 으로 보인다.
# 이 함수가 표식 종류의 정본이다 — 문서에 개수를 적지 않는다(늘리면 문서가 거짓이 된다).
#   HARNESS:UNFILLED — 아직 손대지 않았다. 채우면 결과가 달라진다
#   HARNESS:DEFAULTS — 공통 기본값을 쓰는 중이다. 이것도 정상 상태다
#   HARNESS:NA       — 이 프로젝트에 해당 없는 문서다 (화면 없는 프로젝트의 화면 지침 등)
# /setup 6단계가 공통 기본값이 들어 있는 문서의 표식을 DEFAULTS 로 바꾼다.
ADVISORY=""
USINGDEF=""
NOTAPPL=""
adv() {  # adv <경로> <설명>
  f="$ROOT_DIR/$1"
  if [ ! -f "$f" ]; then
    ADVISORY="$ADVISORY
  - $1 — 파일 없음 ($2)"
    return
  fi
  if grep -q 'HARNESS:NA' "$f"; then
    NOTAPPL="$NOTAPPL
  - $1 — 이 프로젝트에 해당 없음 ($2)"
    return
  fi
  if grep -q 'HARNESS:DEFAULTS' "$f"; then
    USINGDEF="$USINGDEF
  - $1 — 공통 기본값 사용 중 ($2)"
    return
  fi
  # 표식이 남아 있으면 미작성으로 본다 (채운 뒤 그 줄을 지우는 방식)
  if grep -q 'HARNESS:UNFILLED' "$f"; then
    ADVISORY="$ADVISORY
  - $1 — 미작성 ($2)"
  fi
}
adv "CLAUDE.md" "프로젝트 개요 — 없으면 매번 코드에서 추측한다"
adv ".claude/rules/convention.md" "코드 스타일"
adv ".claude/docs/guideline/backend.md" "서버 구현 지침"
adv ".claude/docs/guideline/frontend.md" "화면 구현 지침"
adv ".claude/docs/anti-patterns.md" "사고 이력"
adv ".claude/skills/code-review/references/project-rules.md" "프로젝트 리뷰 기준"
adv ".claude/config/project-meta.yaml" "도메인 용어 사전"

# ── 결과 ────────────────────────────────────────────────
if [ "$LEFT" -eq 0 ] && [ -z "$MISSING" ] && [ -z "$SAMPLE" ] && [ -z "$BADDIR" ]; then
  if [ "$QUIET" -eq 1 ]; then
    echo "연동됨"
  else
    echo "[연동됨] 프로파일 검사 통과 — 채울 것 없음."
    echo "RESULT: BOUND"
    echo
    echo "다음 확인을 함께 한다 (연동 완료 판정은 이 둘까지다):"
    echo "  1) bash .claude/scripts/check-harness-consistency.sh   — 설정과 실제 파일 대조"
    echo "  2) 빌드·테스트 명령을 실제로 한 번 돌려본다 — bash .claude/scripts/check-profile.sh --verify-commands"
    if [ -n "$NOTAPPL" ]; then
      printf '
[해당 없음] 아래 문서는 이 프로젝트에 해당하지 않는다:%s
' "$NOTAPPL"
    fi
    if [ -n "$USINGDEF" ]; then
      printf '\n[정상] 아래 문서는 공통 기본값을 쓰는 중이다 — 이대로 둬도 된다:%s\n' "$USINGDEF"
      echo "  → 프로젝트 고유 규칙을 넣으면 리뷰·구현이 그만큼 정확해진다 (선택)."
    fi
    if [ -n "$ADVISORY" ]; then
      printf '\n[INFO] 권고 문서가 비어 있다 (동작은 하지만 AI 가 프로젝트 사정을 모른다):%s\n' "$ADVISORY"
      echo "  → 작성 안내: .claude/docs/profile/INDEX.md"
      echo "  → 공통 기본값이 이미 들어 있는 문서라면 /setup 이 표식을 정리해 준다."
    fi
  fi
  exit 0
fi

if [ "$QUIET" -eq 1 ]; then
  msg="미연동 — 채울 값 ${LEFT}개"
  [ -n "$SAMPLE" ] && msg="$msg · 예시값 잔존"
  [ -n "$BADDIR" ] && msg="$msg · 없는 모듈 경로"
  echo "$msg"
  exit 3
fi

cat <<MSG
────────────────────────────────────────
[미연동] 이 하네스는 아직 이 프로젝트에 연동되지 않았다.

채울 값 ${LEFT}개.
MSG
[ -n "$REPORT" ] && printf '%s\n' "남은 자리표시자:$REPORT"
if [ -n "$SAMPLE" ]; then
  printf '%s\n' "예시값이 그대로 남아 있다 (예시 프로파일을 복사만 한 상태):$SAMPLE"
  echo "  → config/project.yaml · scope.yaml 의 이름·경로를 이 프로젝트 것으로 바꾼다."
fi
if [ -n "$BADDIR" ]; then
  printf '%s\n' "project.yaml 의 모듈 경로가 실제로 없다:$BADDIR"
fi
[ -n "$MISSING" ] && printf '%s\n' "빠진 키:$MISSING"
cat <<'MSG'

채우는 방법 — 둘 중 하나를 고른다.

  [권고] /setup 을 실행한다.
     코드베이스를 훑어 스택을 알아내고 대부분을 자동으로 채운다.
     개발 지식 없이 답할 수 있는 것만 묻는다.

  [손으로] 아래 셋을 직접 편집한다.
     1. config/project.yaml   이름과 산출물 위치
     2. config/stack.yaml     VCS·빌드·테스트·런타임 명령
     3. config/scope.yaml     AI 가 고칠 수 있는 경로
     스택별로 채워둔 것이 .claude/config/presets/ 에 있다. 복사해서 값만 바꿔도 된다.

안 쓰는 항목은 비우지 말고 false 또는 none 으로 적는다.
(빈 값과 "안 씀" 을 구분해야 하네스가 조용히 건너뛰지 않는다)
────────────────────────────────────────
MSG
echo "RESULT: UNBOUND"
exit 3
