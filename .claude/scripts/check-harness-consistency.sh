#!/usr/bin/env bash
# 하네스 정합성 검사 (결정론 게이트)
#
# 설정과 실제 파일이 어긋났는지 본다. 판정은 사실 기반이며 추론하지 않는다.
# 외부 도구(node·jq·python)에 의존하지 않는다 — 있으면 JSON 검사만 추가로 한다.
#
# 검사 항목은 아래 본문이 정본이다. 여기에 목록을 복사해 두지 않는다 —
# 검사를 늘리거나 빼면 이 주석이 따라오지 않아 거짓이 된다(실측 — 실제로 어긋났다).
# 지금 무엇을 검사하는지는 그냥 실행해서 검사 이름을 보면 된다.
#
# config-keys-exist 와 doc-refs-exist 는 짝이다 —
# 앞은 "없는 설정 키", 뒤는 "없는 스킬·에이전트·파일" 을 잡는다.
# 둘 다 "가리키는 쪽만 남고 대상이 사라진" 사고를 막는다. 사람 눈으로는 안 걸린다.
#
# 사용:  bash .claude/scripts/check-harness-consistency.sh [--show-keys]
# 종료:  0 = 전부 통과 / 1 = 실패 있음

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLAUDE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT="${HARNESS_ROOT:-$(cd "$CLAUDE_DIR/.." && pwd)}"
CONFIG="$CLAUDE_DIR/config"

# --show-keys : config-keys-exist 검사가 실제로 대조한 키 목록을 찍는다.
#   개수만 보면 검사 밖으로 빠진 키를 알 수 없다.
SHOWKEYS=0
for arg in "$@"; do
  case "$arg" in
    --show-keys) SHOWKEYS=1 ;;
    -h|--help)
      echo "usage: $(basename "$0") [--show-keys]"
      echo "  --show-keys   config-keys-exist 검사가 대조한 설정 키 목록을 함께 출력"
      exit 0 ;;
  esac
done

FAIL=0
PASSN=0
SKIPN=0
WARNN=0

ok()   { printf 'PASS  %-22s %s\n' "$1" "$2"; PASSN=$((PASSN+1)); }
bad()  { printf 'FAIL  %-22s %s\n' "$1" "$2"; FAIL=$((FAIL+1)); }
skip() { printf 'SKIP  %-22s %s\n' "$1" "$2"; SKIPN=$((SKIPN+1)); }
warn() { printf 'WARN  %-22s %s\n' "$1" "$2"; WARNN=$((WARNN+1)); }

echo "────────────────────────────────────────"
echo "하네스 정합성 검사"
echo "  워크스페이스: $ROOT"
# 받은 판을 찍는다. zip 으로 배포하면 커밋 해시가 없어 받은 쪽이 자기 판을 식별할 수 없고,
# 마찰 보고가 어느 판의 것인지 가릴 수 없다(검사 종수가 회차마다 늘어 특히 그렇다).
if [ -f "$CLAUDE_DIR/VERSION" ]; then
  printf '  하네스 판: %s (%s)
' "$(sed -n 1p "$CLAUDE_DIR/VERSION" | tr -d "
")" "$(sed -n 2p "$CLAUDE_DIR/VERSION" | tr -d "
")"
else
  echo "  하네스 판: 알 수 없음 (.claude/VERSION 없음 — 갱신이 부분적으로 됐을 수 있다)"
fi
echo "────────────────────────────────────────"

# 1) 프로파일 연동 여부 ─────────────────────────────────
if bash "$SCRIPT_DIR/check-profile.sh" --quiet >/dev/null 2>&1; then
  ok "profile-bound" "프로파일 채워짐"
else
  bad "profile-bound" "미연동 — check-profile.sh 를 실행해 남은 항목을 확인한다"
fi

# 2) project.yaml 의 모듈 경로가 실제로 있는가 ───────────
DIRS=$(grep -E '^[[:space:]]+dir:' "$CONFIG/project.yaml" 2>/dev/null \
       | awk -F'dir:' '{print $2}' | awk '{print $1}' | sed 's/^"//; s/"$//')
if [ -z "$DIRS" ]; then
  skip "project-dirs" "dir 키 없음"
else
  missing=""
  for d in $DIRS; do
    case "$d" in
      __FILL__*) continue ;;
    esac
    [ -d "$ROOT/$d" ] || missing="$missing $d"
  done
  if [ -z "$missing" ]; then
    ok "project-dirs" "모듈 경로 전부 존재"
  else
    bad "project-dirs" "없는 경로:$missing"
  fi
fi

# 3) scope.yaml 허용 경로의 기준 디렉터리가 있는가 ────────
#    protectedPaths 는 존재 검사 대상이 아니다 — 없는 것이 정상일 수 있다
PATHS=$(awk '
  /^[A-Za-z]/            { collect = ($0 ~ /^(sharedModules|scopes)/) ? 1 : 0 }
  /^[[:space:]]+protectedPaths:/ { collect = 0 }
  /^[[:space:]]+(allowedPaths|refReadPaths):/ { collect = 1 }
  collect && /^[[:space:]]*-[[:space:]]/ { print }
' "$CONFIG/scope.yaml" 2>/dev/null \
        | sed 's/^[[:space:]]*-[[:space:]]*//; s/[[:space:]]*#.*//' \
        | grep -v ':' | grep -v '^__FILL__' | sed 's|/\*\*.*$||; s|/\*$||')
if [ -z "$PATHS" ]; then
  skip "scope-paths" "허용 경로 미기재"
else
  missing=""
  for p in $PATHS; do
    case "$p" in
      ""|"."|/*) continue ;;
    esac
    if [ ! -e "$ROOT/$p" ]; then missing="$missing $p"; fi
  done
  if [ -z "$missing" ]; then
    ok "scope-paths" "허용 경로 전부 존재"
  else
    # 신규 프로젝트에서는 "앞으로 만들 경로" 가 정상이다. 오타와 구분할 수 없으므로 WARN 이다.
    warn "scope-paths" "아직 없는 경로(오타 또는 미생성):$missing"
  fi
fi

# 3-2) main 소스 경로에 대응하는 test 경로가 스코프에 있는가 (R13) ──
#    왜 있나: 스코프가 `.../main/java/x/**` 를 쓰기 허용하면서 `.../test/java/x/**` 를
#    빠뜨리면, 그 패키지를 **만들 수는 있는데 테스트할 파일은 못 만드는** 상태가 된다.
#    2026-09-01 실측 — 구현이 전부 끝난 뒤 /qa-test 단계에서야 드러났다. 그 시점에
#    스코프를 고치려면 하네스 파일 수정이라 사용자 확인 절차를 타고 진행이 멈춘다.
#    연동 직후에 알려주는 것이 값이 싸다.
#    WARN 인 이유: 테스트를 두지 않기로 한 프로젝트가 정상적으로 존재한다.
# allowedPaths 의 **디렉터리 글로브(`/**`)만** 본다. 좁게 잡는 이유:
#   - refReadPaths 는 읽기 전용이라 테스트 짝이 필요 없다
#   - 단일 파일(`Foo.java`)은 테스트 파일명 규약이 갈려(`FooTest`·`FooTests`·`FooSpec`) 짝을 못 만든다
#   - 리소스·설정은 짝이 없는 것이 정상이다
# 좁게 잡아 오탐 0 을 우선한다. WARN 이 시끄러우면 아무도 안 본다.
WPATHS=$(awk '
  /^[A-Za-z]/                                  { collect = 0 }
  /^[[:space:]]+allowedPaths:/                 { collect = 1; next }
  /^[[:space:]]+(refReadPaths|protectedPaths):/ { collect = 0 }
  collect && /^[[:space:]]*-[[:space:]]/ { print }
  collect && /^[[:space:]]*[A-Za-z]/     { collect = 0 }
' "$CONFIG/scope.yaml" 2>/dev/null         | sed 's/^[[:space:]]*-[[:space:]]*//; s/[[:space:]]*#.*//; s/[[:space:]]*$//'         | grep -v '^__FILL__' | grep '/\*\*$' | sed 's|/\*\*$||' | sort -u)

if [ -n "$WPATHS" ]; then
  tmiss=""
  for p in $WPATHS; do
    case "$p" in
      *"/main/java/"*|*"/main/kotlin/"*) ;;   # 테스트 루트 규약이 분명한 것만
      *) continue ;;
    esac
    cand=$(printf '%s' "$p" | sed 's|/main/java/|/test/java/|; s|/main/kotlin/|/test/kotlin/|')
    found=0
    for q in $WPATHS; do
      [ "$q" = "$cand" ] && { found=1; break; }
    done
    [ "$found" -eq 0 ] && tmiss="$tmiss $p/**"
  done
  if [ -z "$tmiss" ]; then
    ok "scope-test-paths" "main 소스 경로마다 대응 test 경로 있음"
  else
    warn "scope-test-paths" "test 경로가 어느 스코프에도 없다(그 코드를 테스트할 파일을 못 만든다):$tmiss"
  fi
fi

# 4) 스킬이 프로파일 검사 단계를 갖고 있는가 ─────────────
#    설정 슬롯만 있고 읽는 곳이 없어지는 것을 막는 검사다
GATED="develop git qa-test code-review"
missing=""
for s in $GATED; do
  f="$CLAUDE_DIR/skills/$s/SKILL.md"
  [ -f "$f" ] || { missing="$missing $s(파일없음)"; continue; }
  grep -q "check-profile.sh" "$f" || missing="$missing $s"
done
if [ -z "$missing" ]; then
  ok "skill-profile-gate" "대상 스킬 전부 프로파일 검사 호출"
else
  bad "skill-profile-gate" "호출 없는 스킬:$missing"
fi

# 5) 스킬·에이전트 frontmatter 에 name 이 있는가 ──────────
missing=""
for f in "$CLAUDE_DIR"/skills/*/SKILL.md "$CLAUDE_DIR"/agents/*.md; do
  [ -f "$f" ] || continue
  head -1 "$f" | grep -q '^---' || { missing="$missing $(basename "$(dirname "$f")")/$(basename "$f")"; continue; }
  sed -n '1,10p' "$f" | grep -q '^name:' || missing="$missing $(basename "$f")"
done
if [ -z "$missing" ]; then
  ok "frontmatter-name" "전부 name 보유"
else
  bad "frontmatter-name" "누락:$missing"
fi

# 6) settings.json 파싱 (파서가 있을 때만) ────────────────
SETTINGS="$CLAUDE_DIR/settings.json"
if [ ! -f "$SETTINGS" ]; then
  # 파일이 없으면 훅도 권한 deny 도 없다 — 마찰 수집이 꺼진 채로 "연동됨" 이 난다.
  # SKIP 으로 두면 그 사실이 결과에 안 남아 아무도 모른다(2026-09-01 실측).
  warn "settings-json" "settings.json 없음 — 훅·권한이 배포되지 않았다. 마찰 수집이 꺼져 있다 (.claude/settings.json.example 참고)"
elif command -v node >/dev/null 2>&1; then
  if node -e "JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'))" "$SETTINGS" 2>/dev/null; then
    ok "settings-json" "JSON 유효"
    _hookwired=0
    grep -q 'friction-log' "$SETTINGS" 2>/dev/null && _hookwired=1
    if [ "$_hookwired" = "0" ]; then
      warn "settings-hook" "settings.json 에 마찰 로그 훅이 없다 — /harness-review 집계가 항상 빈다"
    else
      ok "settings-hook" "마찰 로그 훅 배선됨"
    fi
    # 프로젝트가 선언한 보호 경로가 deny 에 반영됐는지 본다.
    # scope.yaml 은 protectedPaths 를 선언하는데 훅·deny 는 자기 하드코딩 목록만 봐서,
    # 선언한 경로가 아무 신호도 안 내는 상태가 된다(2026-09-01 실측).
    # CR 을 걷어낸다 — 설정 파일이 CRLF 면 경로 끝에 제어문자가 붙어 비교가 항상 어긋난다.
    _pp=$(sed -n '/^protectedPaths:/,/^[A-Za-z]/p' "$CONFIG/scope.yaml" 2>/dev/null \
          | sed -n 's/^[[:space:]]*-[[:space:]]*//p' | sed 's/[[:space:]]*#.*$//' | tr -d '\r"' | sed 's/[[:space:]]*$//' | grep -v '^$' | head -20)
    _miss=""
    set -f            # 글로브 확장 금지 — data/** 가 실제 파일 목록으로 부풀어 오른다
    for _p in $_pp; do
      case "$_p" in
        .env|.env.*|"**/*.pem"|__FILL__) continue ;;   # 예시 목록이 이미 덮는 것
      esac
      # %% 는 최장 접미사라 src/main/.../** 가 src 로 줄어 아무 줄에나 맞는다.
      # % 로 최단만 떼고, 정규식이 아니라 고정 문자열로 찾는다(-F).
      _key="${_p%/**}"
      grep -qF -- "$_key" "$SETTINGS" 2>/dev/null || _miss="$_miss $_p"
    done
    set +f
    if [ -n "$_miss" ]; then
      warn "settings-protected" "scope.yaml 의 protectedPaths 가 settings.json 의 deny 에 없다:$_miss"
    fi
  else
    bad "settings-json" "JSON 파싱 실패"
  fi
elif command -v python >/dev/null 2>&1; then
  if python -c "import json,sys;json.load(open(sys.argv[1],encoding='utf-8'))" "$SETTINGS" 2>/dev/null; then
    ok "settings-json" "JSON 유효"
  else
    bad "settings-json" "JSON 파싱 실패"
  fi
else
  skip "settings-json" "JSON 파서 없음 (node·python 부재)"
fi

# 7) semgrep 규칙 파일은 ASCII 전용이어야 한다 ────────────
#    윈도우 semgrep 은 시스템 코드페이지로 규칙을 읽어 한글이 있으면 로드가 실패한다
SEM_DIR="$CLAUDE_DIR/semgrep-rules"
if [ ! -d "$SEM_DIR" ]; then
  skip "semgrep-ascii" ".claude/semgrep-rules/ 없음"
else
  bad_files=""
  for f in "$SEM_DIR"/*.yaml "$SEM_DIR"/*.yml; do
    [ -f "$f" ] || continue
    if LC_ALL=C grep -q '[^ -~	]' "$f"; then
      bad_files="$bad_files $(basename "$f")"
    fi
  done
  if [ -z "$bad_files" ]; then
    ok "semgrep-ascii" "규칙 파일 ASCII 전용"
  else
    bad "semgrep-ascii" "비ASCII 문자 포함:$bad_files (윈도우에서 로드 실패)"
  fi
fi


# 8) 산출물 경로를 하드코딩하지 않았는가 ─────────────────
#    outputDir 은 프로젝트마다 다르다(target · .work · docs/works ...).
#    문서·스크립트가 특정 경로를 박으면 그 프로젝트에서만 맞는 안내가 된다.
HARD=$(grep -rl 'target/works' --include="*.md" --include="*.sh" "$CLAUDE_DIR" 2>/dev/null | grep -v 'check-harness-consistency.sh' | tr '
' ' ')
if [ -z "$HARD" ]; then
  ok "outputdir-not-hardcoded" "산출물 경로가 설정 참조로 되어 있음"
else
  bad "outputdir-not-hardcoded" "경로 하드코딩: $HARD"
fi


# 9) 문서가 참조하는 설정 키가 실제로 있는가 ─────────────
#    슬롯만 만들고 읽는 곳이 없어지는 것과 반대 방향의 사고를 막는다 —
#    문서가 없는 키를 가리키면 그 지시는 조용히 무력화된다.
#    수집 대상: {{config.키}} 표기 + 백틱으로 감싼 점 표기(`stack.yaml` 의 섹션.키)
key_file() {  # key_file <루트키>
  case "$1" in
    projects|customDocs|outputDir|tempDir|workspaceName|planSkill) echo "project.yaml" ;;
    scopes|sharedModules|protectedPaths)                            echo "scope.yaml" ;;
    vcs|build|test|runtime|db|review|lint|env|toolPaths)             echo "stack.yaml" ;;
    terms|statuses|systems|securityKeywords)                        echo "project-meta.yaml" ;;
    *)                                                              echo "" ;;
  esac
}

# {{config.키}} 표기 - 이름 공간이 project.yaml 이므로 루트를 몰라도 검사 대상이다
RAW_T=$(grep -rhoE '\{\{config\.[A-Za-z.]+(\[\])?[A-Za-z.]*\}\}' --include="*.md" --include="*.sh" "$CLAUDE_DIR" 2>/dev/null | sed 's/{{config\.//; s/}}//; s/\[\]//g' | grep -vE '^(xxx|KEY|key|...)$' | sort -u)
# 백틱 점 표기 - 섹션 이름으로 파일을 가른다.
#   문서는 같은 키를 두 가지로 쓴다 — `test.suites` 와 `stack.test.suites`.
#   접두(stack.·config.)를 허용하지 않으면 접두가 붙은 참조가 통째로 검사 밖으로 빠진다.
#   2026-08-31 실측 — 접두형 13곳이 검사되지 않은 채 "전부 존재" PASS 가 났다.
#   검사를 안 하고 통과를 내는 것이 검사 없음보다 나쁘다.
RAW_P=$(grep -rhoE '`((stack|config)\.)?(vcs|build|test|runtime|db|review|lint|scopes|projects|customDocs|sharedModules|protectedPaths|outputDir|tempDir|workspaceName|planSkill|terms|statuses|systems|securityKeywords)\.[A-Za-z.]+(\[\])?[A-Za-z.]*`' --include="*.md" "$CLAUDE_DIR" 2>/dev/null | tr -d '`' | sed 's/^stack\.//; s/^config\.//; s/\[\]//g' | sort -u)

BADKEY=""
CHECKED=0

CHECKEDLIST=""

check_key() {  # check_key <키> <파일>
  leaf="${1##*.}"
  [ -f "$CONFIG/$2" ] || return 0
  CHECKED=$((CHECKED+1))
  CHECKEDLIST="$CHECKEDLIST ${1}"
  # 주석으로 예시만 있는 키도 존재로 인정한다(env 하위 항목 등)
  grep -qE "^[[:space:]]*#?[[:space:]-]*${leaf}[[:space:]]*:" "$CONFIG/$2" || BADKEY="$BADKEY ${1}(->${2})"
}

while IFS= read -r key; do
  [ -z "$key" ] && continue
  check_key "$key" "project.yaml"
done <<EOF
$RAW_T
EOF

while IFS= read -r key; do
  [ -z "$key" ] && continue
  file=$(key_file "${key%%.*}")
  [ -z "$file" ] && continue
  check_key "$key" "$file"
done <<EOF
$RAW_P
EOF

# 단독 최상위 키 표기 — `securityKeywords` 처럼 점이 없는 키는 위 두 규칙이 못 잡는다.
# 그런 키는 검사 사각지대가 되어, 읽는 쪽이 사라져도 아무도 알려주지 않는다
# (2026-09-01 실측 — project-meta.yaml 의 키 전부가 이 사각지대에 있었다).
RAW_S=$(grep -rhoE '`(terms|statuses|systems|securityKeywords|sharedModules|protectedPaths|outputDir|tempDir|workspaceName|planSkill|toolPaths)`'         --include="*.md" "$CLAUDE_DIR" 2>/dev/null | tr -d '`' | sort -u)
while IFS= read -r key; do
  [ -z "$key" ] && continue
  file=$(key_file "$key")
  [ -z "$file" ] && continue
  check_key "$key" "$file"
done <<EOF
$RAW_S
EOF

if [ -z "$BADKEY" ]; then
  ok "config-keys-exist" "문서 참조 키 ${CHECKED}개 전부 설정에 존재"
else
  bad "config-keys-exist" "설정에 없는 키를 문서가 참조:$BADKEY"
fi

# 검사한 키를 실제로 찍는다.
#   개수만 내면 "무엇이 검사 밖으로 빠졌는지" 를 알 방법이 없다 —
#   2026-08-31 사고의 본질이 그것이었다(13곳 누락, 그래도 PASS).
#   목록이 보이면 빠진 키를 사람이 눈으로 찾을 수 있다.
if [ "$SHOWKEYS" -eq 1 ]; then
  echo "  검사한 키 (${CHECKED}개):"
  for k in $CHECKEDLIST; do echo "    - $k"; done
else
  echo "  (검사한 키 목록을 보려면 --show-keys)"
fi


# 10) 문서가 가리키는 대상이 실재하는가 ────────────────────
#    config-keys-exist 의 짝이다. 그쪽은 "없는 설정 키" 를, 이쪽은 "없는 스킬·에이전트·파일" 을 잡는다.
#    ⚠ 검사를 번호로 가리키지 않는다 — 사이에 하나 끼우면 뒤 번호가 전부 밀려 문서가 거짓이 된다.
#       가리킬 때는 항상 검사 이름(ok/warn/bad 의 첫 인자)을 쓴다.
#    ⚠ 검사 이름에 점(`.`)을 쓰지 않는다. 하이픈만 쓴다.
#       config-keys-exist 가 `블록.키` 형태를 설정 키로 보고 대조하므로,
#       `vcs.identity` 처럼 이름을 지으면 문서가 그것을 언급한 순간
#       "설정에 없는 키" FAIL 이 난다(2026-09-01 실측 — 실제로 났다).
#    2026-08-31 실측 사고 2건이 여기 걸린다.
#      - dev-interview 가 Skill(parse-spec-doc) 을 부르는데 그 스킬이 배포본에 없었다.
#      - code-review 가 semgrep 규칙 파일을 잘못된 이름으로 가리켰고,
#        semgrep 이 종료 코드 0 을 내는 바람에 "0건 — 깨끗함" 으로 오독됐다.
#    공통점 — 가리키는 쪽만 남고 대상이 사라졌다. 사람 눈으로는 안 걸린다.
MISSREF=""
REFN=0

# 10-a) Skill(x) / Skill(skill="x") 호출 대상이 skills/x/ 로 존재하는가
for name in $(grep -rhoE 'Skill\((skill=)?["'"'"']?[a-z][a-z0-9-]+' --include="*.md" "$CLAUDE_DIR" 2>/dev/null \
              | sed -E 's/.*Skill\((skill=)?["'"'"']?//' | sort -u); do
  # 문법 설명용 자리표시자와 와일드카드(explore-* 같은 금지 예시)는 대상이 아니다
  case "$name" in skill|name|x|xxx|harness-review|*-) continue ;; esac
  REFN=$((REFN+1))
  [ -d "$CLAUDE_DIR/skills/$name" ] || MISSREF="$MISSREF skill:$name"
done

# 10-b) Agent(subagent_type="x") 호출 대상이 agents/x.md 로 존재하는가
for name in $(grep -rhoE 'subagent_type[[:space:]]*=[[:space:]]*["'"'"'][a-z][a-z0-9-]+' --include="*.md" "$CLAUDE_DIR" 2>/dev/null \
              | sed -E 's/.*["'"'"']//' | sort -u); do
  case "$name" in job|agent|x|xxx|*-) continue ;; esac
  REFN=$((REFN+1))
  # 세션이 제공하는 범용 에이전트는 파일이 없을 수 있다 — 그건 통과시킨다
  case "$name" in claude|general-purpose|explore|plan) continue ;; esac
  [ -f "$CLAUDE_DIR/agents/$name.md" ] || MISSREF="$MISSREF agent:$name"
done

# 10-c) .claude/ 안을 가리키는 문서상 파일 경로가 실재하는가
for f in $(grep -rhoE '`\.claude/[A-Za-z0-9_./-]+\.(md|sh|yaml|json)`' --include="*.md" "$CLAUDE_DIR" 2>/dev/null \
           | tr -d '`' | sort -u); do
  case "$f" in *"{"*|*"}"*|*settings.json) continue ;; esac
  REFN=$((REFN+1))
  [ -e "$ROOT/$f" ] || MISSREF="$MISSREF file:$f"
done

if [ -z "$MISSREF" ]; then
  ok "doc-refs-exist" "문서가 가리키는 스킬·에이전트·파일 ${REFN}개 전부 실재"
else
  bad "doc-refs-exist" "문서가 가리키는데 없는 대상:$MISSREF"
fi

# 13) 스킬이 grep 으로 찾는 표식이 생산 쪽에 실재하는가 ────
#     스킬 A 가 문서를 만들고 스킬 B 가 그 문서를 grep 해서 동작하는 구조가 있다.
#     두 쪽이 서로 다른 파일이라 한쪽만 고치면 어긋난 것이 어느 쪽에서도 안 보인다.
#     새 설정 키는 config-keys-exist 가 알려 주는데 **새 문구는 아무도 안 알려 준다.**
#     2026-09-01 실측 — /develop 6-4 가 찾는 낱말 4개가 계획서 스키마에 0건이라
#     되먹임 단계가 통째로 안 돌았다. 게이트는 전부 PASS 였다.
#
#     형식: {소비자 파일}|{grep 표식}|{생산자 파일}
#     ⚠ 생산자는 **스키마·템플릿 문서**여야 한다. 실제 산출물이나 프로젝트 설정을 생산자로 등록하면
#     그 값이 정상적으로 변할 때(예: 연동되면 __FILL__ 이 사라진다) 오탐이 난다.
#     ⚠ 소비자가 **셸 스크립트**면 그 표식이 정규식이 아니라 **리터럴 상수**로 들어 있어야 한다.
#     정규식 안에만 있으면 이 검사는 주석이나 정규식 조각을 맞히고, 판정 코드가 바뀌어도 통과한다
#     (2026-09-02 실측 — 판정 정규식을 망가뜨렸는데 계약 검사는 PASS 였다).
#     스킬 문서 소비자는 grep 명령을 문서에 리터럴로 적으므로 이 문제가 없다.
TRIGGERS="skills/develop/SKILL.md|<!-- OPEN -->|docs/agents/dev-planner/references/root-doc-schema.md
skills/develop/SKILL.md|다음 페이즈 인계|docs/agents/dev-planner/references/phase-doc-schema.md
scripts/check-workflow-artifacts.sh|- 트랙2:|skills/qa-test/SKILL.md
scripts/check-workflow-artifacts.sh|- 미검증:|rules/testing.md"
TRN=0; TRBAD=""
while IFS='|' read -r _consumer _mark _producer; do
  [ -z "$_mark" ] && continue
  [ -f "$CLAUDE_DIR/$_consumer" ] || continue
  # 소비자가 실제로 그 표식을 찾고 있는지 먼저 본다 (안 찾으면 검사 대상이 아니다)
  TRN=$((TRN+1))
  # 소비자가 그 표식을 더 안 찾으면 **계약 파기**다. 검사 면제가 아니다.
  # continue 로 넘기면 "표식 3개" 가 "2개" 로 줄고 판정은 PASS 가 난다 —
  # 계약이 깨졌는데 통과가 나고, 개수를 회차 간 비교하는 사람은 없다(2026-09-02 실측).
  # 계약을 없앨 때는 이 TRIGGERS 목록에서 지운다. 그것이 파기를 명시적으로 남기는 형태다.
  if ! grep -qF -- "$_mark" "$CLAUDE_DIR/$_consumer" 2>/dev/null; then
    TRBAD="$TRBAD ${_mark}(소비자가 더 안 찾음:${_consumer})"
    continue
  fi
  if [ ! -f "$CLAUDE_DIR/$_producer" ]; then
    TRBAD="$TRBAD ${_mark}(생산자없음:${_producer})"
  elif ! grep -qF -- "$_mark" "$CLAUDE_DIR/$_producer" 2>/dev/null; then
    TRBAD="$TRBAD ${_mark}(->${_producer})"
  fi
done <<EOF
$TRIGGERS
EOF
if [ "$TRN" -eq 0 ]; then
  skip "skill-trigger-phrases" "검사할 표식 계약 없음"
elif [ -z "$TRBAD" ]; then
  ok "skill-trigger-phrases" "스킬이 찾는 표식 ${TRN}개 전부 생산 쪽 스키마에 실재"
else
  bad "skill-trigger-phrases" "표식 계약이 깨졌다:$TRBAD"
fi

# 14) 코어 소유 파일의 **요구사항 줄**에 도메인 어휘가 없는가 ──
#     코어는 갱신 때 모든 프로젝트로 전파된다. 돈 흐름이 없는 프로젝트에
#     "잔액 부족 TC 포함" 같은 체크박스가 오면 **체크할 수 없는 항목**이 되고,
#     체크할 수 없는 항목은 사람이 건너뛰기 시작해 체크리스트 전체가 죽는다.
#
#     **요구사항 줄(`- [ ]`)만 본다.** 예시·설명 산문은 보지 않는다 —
#     워크된 예제는 도메인이 있어야 읽히고, 위험 유형 열거도 정상이다.
#     낱말만 보면 6곳이 걸리는데 그중 실제 결함은 0이었다(2026-09-01 실측).
#     구조로 거르면 오탐이 사라진다.
DOMAIN_WORDS='잔액|가맹점|일/월 한도|결제 흐름|정산 금액|E00[0-9]'
CORE_DIRS="$CLAUDE_DIR/agents $CLAUDE_DIR/docs/agents $CLAUDE_DIR/skills $CLAUDE_DIR/docs/profile"
DOMHIT=$(grep -rnE "^[[:space:]]*- \[ \].*($DOMAIN_WORDS)" $CORE_DIRS --include='*.md' 2>/dev/null \
         | grep -vE '^[^:]*examples[^:]*:' | head -10)
if [ -n "$DOMHIT" ]; then
  bad "core-domain-free" "코어의 요구사항 줄에 특정 도메인 어휘가 있다 — 다른 스택 프로젝트에 그대로 전파된다"
  printf '%s\n' "$DOMHIT" | sed 's|^'"$CLAUDE_DIR"'/|        |'
else
  ok "core-domain-free" "코어 요구사항 줄에 도메인 어휘 없음"
fi

# 15) 산출물이 무시 목록에 들어 있는가 ──────────────────
#     /setup 8단계가 "남은 일" 로 안내하지만 **실행했는지 확인하는 쪽이 없었다.**
#     안 하면 산출물·인수인계·마찰 로그가 커밋 후보로 남고, git add -A 한 번에 쓸려 들어간다
#     (2026-09-02 실측). settings-protected 가 scope.yaml 과 settings.json 을 대조하는 것과 같은 형태다.
IGN_KIND="$(sed -n '/^vcs:/,/^[A-Za-z]/p' "$CONFIG/stack.yaml" 2>/dev/null \
            | sed -n 's/^[[:space:]]*kind:[[:space:]]*//p' | sed 's/[[:space:]]*#.*//' | tr -d '"\r' | head -1)"
OUTDIR="$(sed -n 's/^outputDir:[[:space:]]*//p' "$CONFIG/project.yaml" 2>/dev/null \
          | sed 's/[[:space:]]*#.*//' | tr -d '"\r' | sed 's/[[:space:]]*$//' | head -1)"
case "$IGN_KIND" in
  git)
    if [ ! -f "$CLAUDE_DIR/../.gitignore" ]; then
      warn "outputs-ignored" ".gitignore 가 없다 — 산출물·인수인계·마찰 로그가 커밋 후보로 남는다"
    else
      IGNMISS=""
      for pat in "$OUTDIR" "HANDOFF" "HARNESS_" "harness-friction"; do
        [ -z "$pat" ] || [ "$pat" = "__FILL__" ] && continue
        grep -qF -- "$pat" "$CLAUDE_DIR/../.gitignore" 2>/dev/null || IGNMISS="$IGNMISS $pat"
      done
      if [ -n "$IGNMISS" ]; then
        warn "outputs-ignored" ".gitignore 에 없다(로컬 전용이어야 한다):$IGNMISS"
      else
        ok "outputs-ignored" "산출물·로컬 전용 파일이 무시 목록에 있음"
      fi
    fi ;;
  svn)
    if svn propget svn:ignore . >/dev/null 2>&1; then
      ok "outputs-ignored" "svn:ignore 설정됨 (내용은 사람이 확인한다)"
    else
      warn "outputs-ignored" "svn:ignore 가 없다 — svn add 시 산출물이 쓸려 들어간다"
    fi ;;
  *)
    skip "outputs-ignored" "vcs.kind: ${IGN_KIND:-미설정} — 무시 목록이 필요 없다" ;;
esac




echo "────────────────────────────────────────"
printf '통과 %d · 실패 %d · 경고 %d · 건너뜀 %d\n' "$PASSN" "$FAIL" "$WARNN" "$SKIPN"
echo "────────────────────────────────────────"

if [ "$FAIL" -eq 0 ]; then
  echo "RESULT: PASS"
  exit 0
fi
echo "RESULT: FAIL"
exit 1
