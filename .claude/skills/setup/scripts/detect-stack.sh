#!/usr/bin/env bash
# detect-stack.sh — 연동에 필요한 값을 코드베이스에서 찾아낸다
#
# /setup 1단계 전용. 추측하지 않고 파일 존재·내용으로만 판정한다.
# LLM 이 눈으로 훑어 맞히는 것보다 이 편이 재현 가능하고 빠르다.
#
# 무엇을 찾는지는 아래 본문 절 제목이 정본이다. 여기에 목록을 복사해 두지 않는다 —
# 찾는 것을 늘리면 이 주석이 따라오지 않아 거짓이 된다(실측 — 실제로 어긋났다).
# 어떤 키가 나오는지는 그냥 실행해 보면 된다. 출력이 곧 목록이다.
#
# 사용:  bash .claude/skills/setup/scripts/detect-stack.sh
# 출력:  KEY=VALUE 줄. 값이 없으면 그 키를 내지 않는다.
# 종료:  0 = 항상 (판정은 /setup 이 한다. 이 스크립트는 사실만 보고한다)

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLAUDE_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ROOT="${HARNESS_ROOT:-$(cd "$CLAUDE_DIR/.." && pwd)}"
cd "$ROOT" || exit 0

emit() { printf '%s=%s\n' "$1" "$2"; }

# 스캔에서 빼는 곳 — 남의 코드·생성물·하네스 자신이다. 여기까지 뒤지면 오탐만 늘어난다.
# .claude 를 빼는 이유 — 하네스 안에 common·docs 디렉터리가 있어서
# 그것이 프로젝트의 공통 패키지로 잡힌다(실측 오탐).
PRUNE='-name node_modules -o -name .git -o -name .claude -o -name target -o -name build
       -o -name dist -o -name vendor -o -name .venv -o -name venv -o -name __pycache__
       -o -name .gradle -o -name .work -o -name .idea -o -name .vscode'

echo "# detect-stack.sh 결과 (사실만. 판정은 /setup 이 한다)"
emit ROOT "$ROOT"

# ── 0) 여기가 하네스 골자 저장소인가 ─────────────────────
# 압축을 푼 폴더에는 .claude/ 가 이미 있어 거기서 세션을 여는 것이 자연스럽다.
# 그러면 스킬이 정상 발동하고, /setup 이 **골자 저장소 자신을 프로젝트로 연동**한다.
# 게이트도 통과해서 "연동 완료" 가 나오는데 실제로는 아무 프로젝트에도 안 붙은 상태다
# (2026-09-02 실측). 조용히 틀린 대상에 붙으므로 즉시 드러나는 오류보다 나쁘다.
#
# CLAUDE.md.template 은 갱신 복사 목록에 없어 프로젝트로 따라가지 않는다 — 골자에만 있다.
if [ -f CLAUDE.md.template ] && [ -f .claude/skills/setup/SKILL.md ] && [ ! -f CLAUDE.md ]; then
  emit IS_SKELETON "true"
  emit NOTE_SKELETON "여기는 하네스 골자 저장소다. 연동 대상 프로젝트가 아니다 — .claude/ 폴더를 프로젝트로 복사한 뒤 그 폴더에서 세션을 다시 연다"
fi

# ── 1) 빌드 파일 ────────────────────────────────────────
FOUND=""
has() { [ -f "$1" ] && { FOUND="$FOUND $1"; return 0; }; return 1; }

has pom.xml            && emit FILE_POM              "pom.xml"
has build.gradle       && emit FILE_GRADLE           "build.gradle"
has build.gradle.kts   && emit FILE_GRADLE_KTS       "build.gradle.kts"
has package.json       && emit FILE_PACKAGE_JSON     "package.json"
has go.mod             && emit FILE_GO_MOD           "go.mod"
has pyproject.toml     && emit FILE_PYPROJECT        "pyproject.toml"
has requirements.txt   && emit FILE_REQUIREMENTS     "requirements.txt"
has setup.py           && emit FILE_SETUP_PY         "setup.py"
has Makefile           && emit FILE_MAKEFILE         "Makefile"
has Cargo.toml         && emit FILE_CARGO            "Cargo.toml"

# ── 2) 프리셋 후보 ──────────────────────────────────────
# 순서가 곧 우선순위다. Java 쪽을 먼저 보는 이유 —
# Spring + 화면(React) 구조에서는 pom.xml 과 package.json 이 함께 있고,
# 그 경우 주 스택은 Java 쪽이다. package.json 은 화면 하위 모듈이다.
PRESET=""
if [ -f pom.xml ]; then
  if grep -qi "spring-boot" pom.xml 2>/dev/null; then PRESET="spring-maven"
  else PRESET="spring-maven"; emit NOTE_POM_NO_SPRING "pom.xml 에 spring-boot 가 없다 — 명령은 maven 표준이라 그대로 쓸 수 있으나 확인이 필요하다"; fi
elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then
  PRESET="spring-gradle"
elif [ -f package.json ]; then
  PRESET="node-npm"
elif [ -f go.mod ]; then
  PRESET="go"
elif [ -f pyproject.toml ] || [ -f requirements.txt ] || [ -f setup.py ]; then
  PRESET="python-pytest"
else
  PRESET="none"
fi
emit PRESET_CANDIDATE "$PRESET"
[ -n "$FOUND" ] && emit BUILD_FILES "$(echo "$FOUND" | sed 's/^ //')"

# 빌드 파일이 둘 이상이면 알린다 — 다중 모듈일 수 있고 그때는 사람이 판단해야 한다
CNT=$(echo "$FOUND" | wc -w)
[ "$CNT" -gt 1 ] && emit NOTE_MULTI_BUILD "빌드 파일이 ${CNT}개다. 다중 모듈이면 projects[] 를 나눠야 한다"

# ── 3) 래퍼·스크립트 이름 ───────────────────────────────
[ -f mvnw ] || [ -f mvnw.cmd ] && emit WRAPPER_MAVEN "mvnw"
[ -f gradlew ] || [ -f gradlew.bat ] && emit WRAPPER_GRADLE "gradlew"

if [ -f package.json ]; then
  # scripts 블록의 키만 뽑는다. 프리셋이 가정한 이름이 실제로 있는지 확인용이다
  SCRIPTS=$(sed -n '/"scripts"[[:space:]]*:/,/}/p' package.json 2>/dev/null \
            | grep -oE '"[a-zA-Z0-9:_-]+"[[:space:]]*:' \
            | sed 's/"//g; s/[[:space:]]*:$//' | grep -v '^scripts$' | tr '\n' ' ')
  [ -n "$SCRIPTS" ] && emit NPM_SCRIPTS "$(echo "$SCRIPTS" | sed 's/[[:space:]]*$//')"
  for k in test build dev typecheck lint start; do
    echo "$SCRIPTS" | tr ' ' '\n' | grep -qx "$k" || emit "NPM_MISSING_$(echo "$k" | tr 'a-z' 'A-Z')" "package.json scripts 에 '$k' 가 없다"
  done
fi

# ── 3-2) 화면 하위 모듈의 package.json (R2) ─────────────
# Spring + React 는 package.json 이 frontend/ 에 있고 루트에는 없다. 흔한 구조인데
# 위 3) 이 루트만 봐서 신호가 통째로 비었다(2026-09-01 실측 — 프리셋이 가정한
# 스크립트가 실제로 있는지 확인할 근거가 하나도 없었다).
# 루트 것과 구분되게 FE_ 접두어로 낸다.
for fd in frontend web client ui webapp; do
  [ -f "$fd/package.json" ] || continue
  emit FE_PACKAGE_JSON "$fd/package.json"
  FSCRIPTS=$(sed -n '/"scripts"[[:space:]]*:/,/}/p' "$fd/package.json" 2>/dev/null \
             | grep -oE '"[a-zA-Z0-9:_-]+"[[:space:]]*:' \
             | sed 's/"//g; s/[[:space:]]*:$//' | grep -v '^scripts$' | tr '\n' ' ')
  [ -n "$FSCRIPTS" ] && emit FE_NPM_SCRIPTS "$(echo "$FSCRIPTS" | sed 's/[[:space:]]*$//')"
  for k in test build dev lint; do
    echo "$FSCRIPTS" | tr ' ' '\n' | grep -qx "$k" \
      || emit "FE_NPM_MISSING_$(echo "$k" | tr 'a-z' 'A-Z')" "$fd/package.json scripts 에 '$k' 가 없다"
  done
  # 명령을 만들 때 쓸 접두어. `npm --prefix {여기} run {스크립트}` 형태가 된다
  emit FE_NPM_PREFIX "$fd"
  break
done

# ── 4) 소스 루트 ────────────────────────────────────────
# allowedPaths 후보다. 흔한 이름을 우선순위대로 본다
for d in src app lib pkg cmd internal source; do
  [ -d "$d" ] && emit SOURCE_ROOT "$d" && break
done
# 화면이 따로 있는 구조 — /develop 스코프를 나눌 근거가 된다
for d in frontend web client ui webapp; do
  [ -d "$d" ] && emit FRONTEND_ROOT "$d" && break
done

# ── 4-2) 화면을 백엔드가 함께 서빙하는가 (R3) ───────────
# 화면 빌드 산출물이 백엔드 정적 경로로 들어가면 검증 대상 주소는 백엔드 포트다.
# 이걸 모르면 아래 10) 의 PORT_HINT(개발 서버 포트)를 baseUrl 에 그대로 넣게 되고,
# 트랙2 검증이 통째로 엉뚱한 곳을 본다(2026-09-01 실측 — vite 5173 vs 실제 8080).
SERVED=""
for cfg in $(find . -maxdepth 3 \( $PRUNE \) -prune -o -type f \
             \( -name "vite.config.*" -o -name "webpack.config.*" -o -name "rollup.config.*" \) \
             -print 2>/dev/null | sed 's|^\./||' | head -5); do
  OUTDIR=$(grep -oE "(outDir|path)[[:space:]]*:[[:space:]]*['\"][^'\"]+['\"]" "$cfg" 2>/dev/null \
           | head -1 | sed "s/^[^'\"]*['\"]//; s/['\"].*$//")
  [ -n "$OUTDIR" ] || continue
  emit FRONTEND_BUILD_OUTDIR "$OUTDIR (출처: $cfg)"
  case "$OUTDIR" in
    *src/main/resources/static*|*src/main/resources/public*|*/public/*|*static*|*wwwroot*)
      SERVED="yes" ;;
  esac
done
if [ "$SERVED" = "yes" ]; then
  emit SERVED_BY_BACKEND "true"
  emit NOTE_SERVED_BY_BACKEND "화면 빌드 산출물이 백엔드 정적 경로로 들어간다 — runtime.baseUrl 은 백엔드 포트를 쓰고 frontendUrl 은 비운다. 아래 PORT_HINT 가 화면 개발 서버 포트일 수 있으니 그대로 쓰지 않는다"
fi

# ── 5) 공통 패키지 후보 ─────────────────────────────────
# sharedModules 후보다. common·shared·util 이름이 붙은 디렉터리를 찾는다.
# 깊이 8 인 이유 — Java·Kotlin 표준 배치는 src/main/java 만으로 3, 3단 패키지(com/example/board)로 6 이라
# 공통 패키지가 항상 7 이상이다. 6 이면 그 계열에서 구조적으로 한 건도 안 잡힌다(2026-09-01 실측).
# PRUNE 이 node_modules·target·.claude 를 이미 쳐내므로 깊이를 올려도 비용이 거의 없다.
COMMON=$(find . -maxdepth 8 \( $PRUNE \) -prune -o -type d \
         \( -name common -o -name shared -o -name util -o -name utils -o -name core \) -print 2>/dev/null \
         | sed 's|^\./||' | head -5 | tr '\n' ' ')
[ -n "$COMMON" ] && emit COMMON_DIRS "$(echo "$COMMON" | sed 's/[[:space:]]*$//')"

# ── 6) 시크릿·보호 경로 후보 ────────────────────────────
# protectedPaths 후보다. 실제로 있는 것만 낸다 — 없는 경로를 넣으면 게이트가 경고한다
SEC=""
add_sec() { [ -e "$1" ] && SEC="$SEC $1"; }
add_sec .env
for f in $(find . -maxdepth 4 \( $PRUNE \) -prune -o -type f \
           \( -name ".env.*" -o -name "*.pem" -o -name "*.p12" -o -name "*.jks" -o -name "*.keystore" \
              -o -name "credentials.json" -o -name "id_rsa" \) -print 2>/dev/null | sed 's|^\./||' | head -10); do
  SEC="$SEC $f"
done
# 운영 설정으로 보이는 파일명 — prod·production 이 들어간 설정
for f in $(find . -maxdepth 5 \( $PRUNE \) -prune -o -type f \
           \( -name "*prod*.properties" -o -name "*prod*.yml" -o -name "*prod*.yaml" -o -name "*production*.json" \) -print 2>/dev/null | sed 's|^\./||' | head -10); do
  SEC="$SEC $f"
done
[ -n "$SEC" ] && emit SECRET_CANDIDATES "$(echo "$SEC" | sed 's/^ //; s/[[:space:]]*$//')"

# 데이터·산출물 디렉터리 — 보호 경로 후보
for d in data uploads storage secrets; do
  [ -d "$d" ] && emit DATA_DIR "$d"
done

# ── 7) 형상관리 ─────────────────────────────────────────
if [ -d .git ]; then
  emit VCS_KIND "git"
  BR=$(git -C "$ROOT" symbolic-ref --short HEAD 2>/dev/null)
  [ -n "$BR" ] && emit VCS_BRANCH "$BR"
  RM=$(git -C "$ROOT" remote 2>/dev/null | head -1)
  if [ -n "$RM" ]; then emit VCS_REMOTE "$RM"; else emit VCS_REMOTE "none"; fi
elif [ -d .svn ]; then
  emit VCS_KIND "svn"
else
  emit VCS_KIND "none"
fi

# ── 8) 프로젝트 이름 후보 ───────────────────────────────
# 폴더명과 빌드 파일의 이름이 다른 경우가 흔하다(실측 3/3).
# 빌드 파일 쪽이 사람들이 부르는 이름에 가깝다 — 그쪽을 우선으로 낸다.
emit DIR_NAME "$(basename "$ROOT")"

PKG=""
if [ -f package.json ]; then
  PKG=$(grep -m1 -oE '"name"[[:space:]]*:[[:space:]]*"[^"]+"' package.json 2>/dev/null         | sed 's/.*"name"[[:space:]]*:[[:space:]]*"//; s/"$//')
elif [ -f pyproject.toml ]; then
  PKG=$(grep -m1 -oE '^[[:space:]]*name[[:space:]]*=[[:space:]]*"[^"]+"' pyproject.toml 2>/dev/null         | sed 's/.*"//;' )
  PKG=$(grep -m1 -oE '^[[:space:]]*name[[:space:]]*=[[:space:]]*"[^"]+"' pyproject.toml 2>/dev/null         | sed 's/.*=[[:space:]]*"//; s/"$//')
elif [ -f pom.xml ]; then
  # <parent> 블록 안의 artifactId 는 상위 POM(spring-boot-starter-parent 등)이라
  # 이 프로젝트 이름이 아니다. 그 구간을 건너뛰고 첫 artifactId 를 고른다.
  # (들여쓰기로 가르려 했더니 탭 들여쓰기에서 부모를 잡았다 — 실측 오탐)
  PKG=$(awk '
    /<parent>/  { skip=1 }
    /<\/parent>/ { skip=0; next }
    !skip && /<artifactId>/ {
      sub(/.*<artifactId>/, ""); sub(/<\/artifactId>.*/, ""); print; exit
    }
  ' pom.xml 2>/dev/null)
elif [ -f go.mod ]; then
  PKG=$(grep -m1 '^module ' go.mod 2>/dev/null | sed 's/^module[[:space:]]*//' | sed 's|.*/||')
fi
[ -n "$PKG" ] && emit PKG_NAME "$PKG"
[ -n "$PKG" ] && [ "$PKG" != "$(basename "$ROOT")" ] &&   emit NOTE_NAME_DIFFERS "빌드 파일 이름($PKG)과 폴더명($(basename "$ROOT"))이 다르다 — 빌드 파일 쪽을 쓴다"

# ── 9) 테스트 디렉터리 ──────────────────────────────────
# allowedPaths 에 넣어야 한다. 안 넣으면 /qa-test 가 테스트를 못 쓴다.
for d in test tests src/test spec __tests__; do
  [ -d "$d" ] && emit TEST_ROOT "$d" && break
done

# ── 10) 기동 포트 후보 ──────────────────────────────────
# runtime.baseUrl 은 프리셋 기본값이 틀리기 쉽다(실측 — vite 5173 vs express 3000).
# 소스에서 포트를 찾아 단서를 낸다.
# 빌드 산출물(minify 된 번들)을 반드시 뺀다 — 거기서 아무 숫자나 잡힌다(실측 오탐 4000).
# --include 는 파일명만 거른다 — node_modules 안의 .js 도 **읽은 뒤에** 버려진다.
# --exclude-dir 로 사전 배제한다(실측 — 합성 트리 12,001파일에서 76ms 대 8ms).
# 뒤의 사후 필터는 그대로 둔다. /static/ 같은 것은 디렉터리명이 아니라 경로 조각이다.
PORTSRC=$(grep -rlE '(PORT[[:space:]]*\|\|[[:space:]]*|[^a-zA-Z]port[[:space:]]*[=:][[:space:]]*|server\.port[[:space:]]*[=:][[:space:]]*)[0-9]{4,5}'        --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=target --exclude-dir=build --exclude-dir=dist --exclude-dir=vendor --exclude-dir=.venv --exclude-dir=venv --exclude-dir=__pycache__ --exclude-dir=.gradle --exclude-dir=.work --include="*.js" --include="*.ts" --include="*.py" --include="*.properties"        --include="*.yml" --include="*.yaml" --include="*.go" . 2>/dev/null        | grep -vE 'node_modules|/static/|/dist/|/build/|/target/|\.min\.' | head -1 | sed 's|^\./||')
if [ -n "$PORTSRC" ]; then
  PORT=$(grep -ohE '(PORT[[:space:]]*\|\|[[:space:]]*|[^a-zA-Z]port[[:space:]]*[=:][[:space:]]*|server\.port[[:space:]]*[=:][[:space:]]*)[0-9]{4,5}'          "$PORTSRC" 2>/dev/null | grep -oE '[0-9]{4,5}' | head -1)
  # 어디서 나온 값인지 함께 낸다 — 근거 없는 값은 검토할 수 없다
  [ -n "$PORT" ] && emit PORT_HINT "$PORT (출처: $PORTSRC)"
fi

# ── 11) 빌드 도구가 PATH 에 있는가 (R1) ─────────────────
# 프리셋 명령은 도구가 PATH 에 있어야 돈다. 없으면 `command not found` 로 전부 죽고,
# 그러면 /develop·/qa-test 가 통째로 무의미해진다. 연동을 완전히 막는 종류다.
# 비개발자는 "PATH 에 없다" 는 진단도, 설치 경로도 모른다 — 후보를 찾아 준다.
# 찾은 후보는 /setup 이 stack.yaml 의 toolPaths 에 넣는다.
need_tool() {   # need_tool <도구> <프리셋조건이 참일 때만>
  [ "$2" = "yes" ] || return 0
  if command -v "$1" >/dev/null 2>&1; then
    emit "TOOL_OK_$(echo "$1" | tr 'a-z-' 'A-Z_')" "$(command -v "$1")"
    return 0
  fi
  emit "TOOL_MISSING_$(echo "$1" | tr 'a-z-' 'A-Z_')" "$1 이(가) PATH 에 없다 — 이 도구를 쓰는 명령은 전부 실패한다"
  # 흔한 설치 경로에서 후보를 찾는다. 찾으면 toolPaths 에 넣을 디렉터리를 낸다.
  for c in \
      "$HOME/.m2/wrapper/dists"/*/*/bin/"$1" \
      "$HOME/.gradle/wrapper/dists"/*/*/*/bin/"$1" \
      "$HOME/.sdkman/candidates/$1/current/bin/$1" \
      "/c/Program Files"/*/bin/"$1" \
      "/c/Program Files"/*/*/bin/"$1" \
      "/usr/local/bin/$1" "/opt/homebrew/bin/$1" "/opt/$1/bin/$1" ; do
    [ -x "$c" ] || continue
    emit "TOOL_PATH_CANDIDATE_$(echo "$1" | tr 'a-z-' 'A-Z_')" "$(dirname "$c")"
    return 0
  done
  emit "TOOL_PATH_UNKNOWN_$(echo "$1" | tr 'a-z-' 'A-Z_')" "설치 경로 후보를 못 찾았다 — 사용자에게 설치 위치를 묻는다"
}

NEED_MVN=no; NEED_GRADLE=no; NEED_NPM=no; NEED_PY=no; NEED_GO=no
case "$PRESET" in
  spring-maven)   NEED_MVN=yes ;;
  spring-gradle)  NEED_GRADLE=yes ;;
  node-npm)       NEED_NPM=yes ;;
  python-pytest)  NEED_PY=yes ;;
  go)             NEED_GO=yes ;;
esac
# 주 스택이 Java 라도 화면이 있으면 npm 이 필요하다
[ -f package.json ] && NEED_NPM=yes
for fd in frontend web client ui webapp; do
  [ -f "$fd/package.json" ] && NEED_NPM=yes && break
done
# 래퍼가 있으면 PATH 에 없어도 된다 — 래퍼로 부르면 되므로 검사에서 뺀다
[ -f mvnw ] || [ -f mvnw.cmd ] && NEED_MVN=no
[ -f gradlew ] || [ -f gradlew.bat ] && NEED_GRADLE=no

need_tool mvn    "$NEED_MVN"
need_tool gradle "$NEED_GRADLE"
need_tool npm    "$NEED_NPM"
# python 은 이름이 갈린다 — python3(리눅스·맥 표준) · py(윈도우 런처) 도 본다.
# 하나라도 있으면 있는 것으로 본다. 안 그러면 정상 설치된 PC 에서 빌드·테스트를 끄게 된다.
if [ "$NEED_PY" = "yes" ]; then
  if command -v python3 >/dev/null 2>&1; then
    emit TOOL_OK_PYTHON "$(command -v python3)"
  elif command -v py >/dev/null 2>&1; then
    emit TOOL_OK_PYTHON "$(command -v py)"
  else
    need_tool python "$NEED_PY"
  fi
fi
need_tool go     "$NEED_GO"

echo "# 끝"
