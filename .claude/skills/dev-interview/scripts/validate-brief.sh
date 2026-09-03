#!/usr/bin/env bash
# validate-brief.sh
# dev-interview Stage 1 형식 게이트 결정론 검증기
#
# 11섹션 개발 브리프({{config.outputDir}}/works/{과업번호}_dev_brief.md)의 출력 계약을
# references/brief-schema.md 의 self-check 항목에 따라 결정론 검증한다.
#
# 검증 항목은 아래 본문의 add_check() 호출이 정본이다.
# 여기에 번호를 붙인 목록을 두지 않는다 — 사이에 하나 끼우면 뒤 번호가 전부 밀린다.
# 지금 무엇을 검사하는지는 그냥 실행해서 반환 JSON 의 checks[].id 를 보면 된다.
#
# 경고(level=warn)는 pass 판정을 막지 않는다 — 인터뷰 없이 브리프를 직접 쓴 경우를
# 정상 경로로 인정한다. 실패(level=fail)만 pass:false 를 만든다.
#
# Usage:
#   bash .claude/skills/dev-interview/scripts/validate-brief.sh -BriefFile <path>
#
# Output:
#   JSON: { "pass": bool, "checks": [...], "failed": n, "file": "..." }
#
# Example:
#   bash .claude/skills/dev-interview/scripts/validate-brief.sh \
#        -BriefFile {{config.outputDir}}/works/057_dev_brief.md

set -euo pipefail

BRIEF_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    -BriefFile|-brieffile) BRIEF_FILE="$2"; shift 2 ;;
    *) echo "ERROR: Unknown argument: $1" >&2; exit 1 ;;
  esac
done

[[ -z "$BRIEF_FILE" ]] && { echo "ERROR: -BriefFile is required" >&2; exit 1; }
[[ ! -f "$BRIEF_FILE" ]] && { echo "ERROR: Brief file not found: $BRIEF_FILE" >&2; exit 1; }

PYTHON=""
for c in python py python3; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c "import sys" >/dev/null 2>&1; then PYTHON="$c"; break; fi
done
[[ -z "$PYTHON" ]] && { echo "ERROR: executable python not found" >&2; exit 1; }

"$PYTHON" - "$BRIEF_FILE" <<'PYEOF'
import sys, json, re
try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

brief_file = sys.argv[1]
with open(brief_file, encoding='utf-8') as f:
    content = f.read()

lines = content.splitlines()
checks = []
failed = 0

warned = 0

def add_check(id_, name, pass_, detail='', level='fail'):
    """level='fail' 이면 실패로 센다. level='warn' 이면 알리기만 한다.

    warn 은 인터뷰를 거쳤을 때만 성립하는 항목에 쓴다 —
    작은 과업에서 브리프를 직접 쓰는 경로를 막지 않기 위한 것이다.
    """
    global failed, warned
    checks.append({'id': id_, 'name': name, 'pass': pass_,
                   'detail': detail, 'level': level})
    if not pass_:
        if level == 'warn':
            warned += 1
        else:
            failed += 1

# --- 1. §1 ~ §11 헤더 존재 + 순서 -------------------------------------------
section_nums = []
for l in lines:
    m = re.match(r'^## (\d+)\.\s', l)
    if m:
        section_nums.append(int(m.group(1)))
missing = [n for n in range(1, 12) if n not in section_nums]
ordered = (section_nums == list(range(1, 12)))
add_check('SEC_HEADERS', '§1 ~ §11 헤더 존재 + 순서',
          len(missing) == 0 and ordered,
          f"missing={','.join(map(str, missing))} headers={','.join(map(str, section_nums))}")

# --- 2. §1 문서 메타 필수 항목 ---------------------------------------------------
meta_keys = ['과업번호', '요구 출처', '작성일', '제안 개발 기간']
missing_meta = [k for k in meta_keys if (f'| {k} |' not in content and f'| {k} ' not in content)]
add_check('META_TABLE', '§1 문서 메타 4항목', len(missing_meta) == 0,
          f"missing={','.join(missing_meta)}")

# --- 3. §2 시스템 결정 6항목 -------------------------------------------------
sys_keys = ['개발 유형', '프로젝트 유형', '프로젝트명', '소스 기준 경로', '빌드', '실행·배포 방식']
missing_sys = [k for k in sys_keys if f'| {k} ' not in content]
add_check('SYSTEM_TABLE', '§2 시스템 결정 6항목', len(missing_sys) == 0,
          f"missing={','.join(missing_sys)}")

# --- 4. §3 하위 절 4개 -------------------------------------------------------
sub_sections = ['3-1', '3-2', '3-3', '3-4']
missing_sub = [s for s in sub_sections
               if not any(re.match(rf'^### {re.escape(s)}\.', l) for l in lines)]
add_check('SCOPE_SUBSECTIONS', '§3 Primary/Related/패턴/영향 하위 절', len(missing_sub) == 0,
          f"missing={','.join(missing_sub)}")

# --- 5. §9 미결사항 비어있지 않음 -------------------------------------------
sec9_idx  = next((i for i, l in enumerate(lines) if re.match(r'^## 9\.', l)), -1)
sec10_idx = next((i for i, l in enumerate(lines) if re.match(r'^## 10\.', l) and i > sec9_idx),
                 len(lines)) if sec9_idx >= 0 else len(lines)
sec9_has_content = False
if sec9_idx >= 0:
    for i in range(sec9_idx + 1, sec10_idx):
        if lines[i].strip() and not lines[i].startswith('##'):
            sec9_has_content = True
            break
add_check('OPEN_QUESTIONS', '§9 미결사항 비어있지 않음', sec9_has_content, '')

# --- 6. §11 Phase ≥ 3 단계 --------------------------------------------------
sec11_idx = next((i for i, l in enumerate(lines) if re.match(r'^## 11\.', l)), -1)
phase_count = 0
if sec11_idx >= 0:
    for i in range(sec11_idx + 1, len(lines)):
        if lines[i].startswith('## '):
            break
        if (re.match(r'^\s*\d+\.\s', lines[i]) or
                re.search(r'(?i)Phase\s*\d+', lines[i]) or
                re.match(r'^\s*-\s+\d+단계', lines[i])):
            phase_count += 1
add_check('IMPL_PHASES', '§11 Phase/단계 ≥ 3', phase_count >= 3, f"count={phase_count}")

# --- 7. TBD / 추후 결정 금지 -------------------------------------------------
forbidden = []
for m in re.finditer(r'(?i)\bTBD\b|추후\s*결정', content):
    line_num = content[:m.start()].count('\n') + 1
    forbidden.append(f"line {line_num}")
add_check('NO_TBD', '"TBD" / "추후 결정" 표현 없음', len(forbidden) == 0,
          f"hits={'; '.join(forbidden)}")

# --- 8. 메타 footer ----------------------------------------------------------
has_explore = '선탐색 시간' in content
has_rounds  = '인터뷰 라운드' in content
has_review  = '검토 등급' in content
add_check('META_FOOTER', '메타 footer (선탐색·라운드·검토 등급)',
          has_explore and has_rounds and has_review,
          f"explore={has_explore} rounds={has_rounds} review={has_review}"
          + ('' if (has_explore and has_rounds and has_review)
             else ' | expected lines: **선탐색 시간**: / **인터뷰 라운드**: / **검토 등급**:'),
          level='warn')

# --- 9. 부록 Q&A 로그 --------------------------------------------------------
has_appendix = any(
    re.match(r'^## 인터뷰 Q&A 로그', l) or re.match(r'^## .*Q&A.*부록', l)
    for l in lines
)
add_check('QNA_APPENDIX', 'Q&A 로그 부록 첨부', has_appendix,
          '' if has_appendix else 'expected header: "## 인터뷰 Q&A 로그 (부록)"',
          level='warn')

# --- 10. 마크다운 표 구조 ----------------------------------------------------
# 왜 있나: 섹션 헤더·항목 이름·Phase 수가 다 맞아도 표가 두 동강 나 있을 수 있다.
#   2026-09-01 실측 — 재인터뷰 결과를 §4-1 표 중간에 끼워 넣어 한 표가 갈렸는데
#   형식 검사가 전부 통과했다. 눈으로 보고 고쳤다. 안 봤으면 깨진 표를 dev-plan 이 읽었을 것이다.
# 무엇을 보나: 구분행(|---|) 바로 위 헤더행과 아래 연속 데이터행의 열 수가 같은지.
def _cols(line):
    t = line.strip()
    if not (t.startswith('|') and t.endswith('|')):
        return None
    return len(t.split('|')) - 2      # 양끝 빈 조각 제외

_sep = re.compile(r'^\s*\|(\s*:?-{2,}:?\s*\|)+\s*$')
bad_tables = []
i = 0
while i < len(lines):
    if _sep.match(lines[i]) and i > 0:
        want = _cols(lines[i])
        head = _cols(lines[i - 1])
        if head is not None and want is not None and head != want:
            bad_tables.append(f'{i}행: 헤더 {head}열 vs 구분행 {want}열')
        j = i + 1
        while j < len(lines):
            c = _cols(lines[j])
            if c is None:
                break                  # 표 끝 (빈 줄·다른 블록)
            if c != want:
                bad_tables.append(f'{j + 1}행: 데이터 {c}열 (표 기준 {want}열)')
                break
            j += 1
        i = j
        continue
    i += 1

add_check('TABLE_SHAPE', '마크다운 표 열 수 일치', not bad_tables,
          '' if not bad_tables else '열 수가 어긋난 표: ' + ' / '.join(bad_tables[:5]))

# --- 출력 -------------------------------------------------------------------
result = {
    'pass':   failed == 0,
    'warned': warned,
    'checks': checks,
    'failed': failed,
    'file':   brief_file,
}
print(json.dumps(result, ensure_ascii=False))
PYEOF
