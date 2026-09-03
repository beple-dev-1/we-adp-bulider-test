#!/usr/bin/env bash
# friction-log.sh — 차단 신호를 마찰 로그에 1줄 append 한다 (PreToolUse 훅)
#
# 왜 있나: harness-review 2단계가 harness-friction.jsonl 을 집계하는데,
#   그것을 쓰는 주체가 없으면 그 단계가 항상 "마찰 없음" 을 내고 무의미해진다.
#   (2026-09-01 실측 — 훅 미배포로 JSONL 이 아예 없었다.)
#
# 무엇을 하나: stdin 으로 받은 훅 입력을 보고, 보호 대상에 닿는 호출이면
#   .claude/harness-friction.jsonl 에 1줄 남긴다.
#   **차단은 settings.json 의 deny 가 한다.** 이 훅은 기록만 하고 항상 0 으로 끝난다 —
#   도구 동작을 바꾸지 않으므로 오탐이 나도 작업을 막지 않는다.
#
# 설계 원칙
#   1) 외부 파서(jq·python·node)에 의존하지 않는다.
#   2) **외부 프로세스를 최소로 띄운다.** 이 훅은 도구 호출마다 도므로 비용이 곧 체감 지연이다.
#      윈도우는 프로세스 기동이 비싸다 — grep/sed 를 남발하면 호출당 수백 ms 가 붙는다
#      (2026-09-01 실측 — 최적화 전 파일 297ms · Bash 650ms).
#      그래서 판정은 bash 내장(`case`·`${var//}`·`[[ ]]`)으로 하고,
#      **관심 없는 입력은 프로세스를 하나도 띄우지 않고 즉시 끝낸다.**
#   3) 값 추출은 느슨하게 한다. 로그용이라 정확한 파싱이 필요 없고,
#      엄격한 파싱은 이스케이프 지옥으로 들어가 훅 자체가 깨진다(실측).
#
# 배포: settings.json 의 hooks.PreToolUse 에 등록한다 (settings.json.example 참조).
# 로그: .claude/harness-friction.jsonl (로컬 전용 — VCS 에 올리지 않는다)

set -u

INPUT=""
# 개행으로 끝나지 않는 stdin 도 받는다. `read` 는 EOF 에서 false 를 리턴하므로
# `|| [ -n "$_line" ]` 이 없으면 마지막 줄이 폐기되고 INPUT 이 빈 문자열이 된다
# — 조용히 exit 0 이 되어 수집이 통째로 멈춘다(2026-09-01 실측).
# 상한을 둔다: 대형 Write/Edit 본문을 전부 읽으면 도구 호출당 초 단위 지연이 붙는다
# (실측 — 1MB 입력 2,078ms). 판정에 필요한 앞부분만 보면 충분하다.
while IFS= read -r -N 65536 _line || [ -n "$_line" ]; do
  INPUT="$INPUT$_line"
  [ "${#INPUT}" -ge 65536 ] && break
done

[ -z "$INPUT" ] && exit 0

# ── 조기 반환 (프로세스 0개) ────────────────────────────
# 관심 있는 문자열이 하나도 없으면 여기서 끝낸다. 대부분의 호출이 여기서 걸러진다.
_lower="${INPUT,,}"
case "$_lower" in
  *.env*|*.pem*|*.p12*|*.jks*|*keystore*|*id_rsa*|*credentials*|*.real.*|*prod.properties*|*prod.yml*|*prod.yaml* ) ;;
  *insert*|*update*|*delete*|*drop*|*truncate*|*alter*|*select* ) ;;
  * ) exit 0 ;;
esac

SCRIPT_DIR="${BASH_SOURCE[0]%/*}"
CLAUDE_DIR="${SCRIPT_DIR%/*}"
LOG="$CLAUDE_DIR/harness-friction.jsonl"

# 타임스탬프 — date 프로세스 1개. 여기까지 온 호출에만 든다.
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# 이름이 `*-attempt` 인 이유 — 이 훅은 **차단 여부를 모른다.**
# 차단은 settings.json 의 deny 가 따로 판정한다. 허용된 접근도 여기 기록되므로
# `*-block` 이라고 쓰면 집계를 읽는 사람이 "막혔다" 로 오독한다.
emit() {  # emit <type> <키> <값>
  v="${3//\\//}"   # 역슬래시 → 슬래시 (윈도우 경로 정규화)
  # 값 추출에 실패했으면 기록하지 않는다. `"table":""` 는 정보가 0이고
  # 집계에서 오탐 비율만 부풀린다.
  [ -z "$v" ] && return 0
  printf '{"ts":"%s","type":"%s","%s":"%s"}\n' "$TS" "$1" "$2" "$v" >> "$LOG"
}

# ── file_path 느슨한 추출 (내장 문자열 연산) ────────────
FILE_PATH=""
_t="${INPUT#*\"file_path\"}"
if [ "$_t" != "$INPUT" ]; then
  _t="${_t#*\"}"          # : 다음 첫 따옴표 뒤
  FILE_PATH="${_t%%\"*}"  # 다음 따옴표 앞
fi

# ── 1) 보호 경로 접근 ───────────────────────────────────
if [ -n "$FILE_PATH" ]; then
  _fp="${FILE_PATH,,}"
  case "$_fp" in
    *.env|*.env.*|*.pem|*.p12|*.jks|*.keystore|*id_rsa*|*credentials.json|*.real.*|*prod.properties|*prod.yml|*prod.yaml)
      emit file-access-attempt path "$FILE_PATH" ;;
  esac
fi

# ── 명령 유형만 아래를 본다 ─────────────────────────────
case "$INPUT" in
  *'"command"'*|*'"script"'*) ;;
  *) exit 0 ;;
esac

# ── 2) 명령 경유 시크릿 열람 ────────────────────────────
case "$_lower" in
  *cat*|*type*|*more*|*less*|*head*|*tail*|*get-content*|*strings*)
    for pat in env pem p12 jks keystore id_rsa credentials; do
      case "$_lower" in
        *".$pat"*|*"/$pat"*|*"$pat "*)
          emit bash-secret-attempt pattern "$pat"; break ;;
      esac
    done ;;
esac

# ── 3) 업무 데이터 직접 조회 ────────────────────────────
# stack.yaml 의 db.metaOnly: true 일 때만. 구조 조회(information_schema 등)는 정상이라 뺀다.
case "$_lower" in
  *select*from*)
    case "$_lower" in
      *information_schema*|*pg_catalog*|*sqlite_master*|*show*|*describe*|*desc*) ;;
      *)
        # metaOnly 판정 — 여기까지 온 호출에만 파일을 읽는다
        _meta=""
        while IFS= read -r _l; do
          _l="${_l%$'\r'}"   # CR 제거 — 리터럴 CR 은 편집 중 LF 로 바뀐다(실측). 이스케이프 표기로 고정
          case "$_l" in
            "  metaOnly:"*) _meta="${_l#*:}"; _meta="${_meta%%#*}"; _meta="${_meta//[^a-z]/}"; break ;;
          esac
        done < "$CLAUDE_DIR/config/stack.yaml" 2>/dev/null
        if [ "$_meta" = "true" ]; then
          _tb="${_lower#*from }"
          _tb="${_tb%% *}"
          _tb="${_tb%%;*}"
          # 따옴표·중괄호·콤마 등 꼬리 문자를 걷어낸다 (느슨한 추출이라 붙어 올 수 있다)
          _tb="${_tb%%[^a-z0-9_.]*}"
          emit business-table-attempt table "$_tb"
        fi ;;
    esac ;;
esac

# ── 4) DB 변경 쿼리 ─────────────────────────────────────
for kw in "insert into" "delete from" "drop table" "drop database" "truncate table" "alter table"; do
  case "$_lower" in
    *"$kw"*) emit dml-attempt keyword "${kw%% *}"; exit 0 ;;
  esac
done
# `*update*set*` 만 보면 부분 문자열이 순서만 맞아도 걸린다.
# 실측 — `svn update && svn commit -m 'settings.json 갱신'` 이 잡혔다.
# `settings` 의 `set` 이 물린 것이고, 하필 둘 다 이 하네스가 시키는 것이다.
# 그래서 흔한 오탐원을 먼저 걷어내고 본다.
_dml="$_lower"
_dml="${_dml//settings/}"; _dml="${_dml//setting/}"; _dml="${_dml//setup/}"
_dml="${_dml//offset/}";   _dml="${_dml//dataset/}"; _dml="${_dml//subset/}"
_dml="${_dml//preset/}"; _dml="${_dml//asset/}"; _dml="${_dml//charset/}"; _dml="${_dml//reset/}"
_dml="${_dml//submodule update/}"; _dml="${_dml//gradlew update/}"; _dml="${_dml//versions:update/}"
_dml="${_dml//\/api\/update/}"   # REST 경로의 /api/update
_dml="${_dml//svn update/}"; _dml="${_dml//npm update/}"; _dml="${_dml//apt update/}"
_dml="${_dml//yum update/}"; _dml="${_dml//brew update/}"; _dml="${_dml//git update/}"
case "$_dml" in
  *update*set*) emit dml-attempt keyword update ;;
esac

exit 0
