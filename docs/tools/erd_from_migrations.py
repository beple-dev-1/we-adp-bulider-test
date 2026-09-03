"""마이그레이션을 재생해 `docs/data-model.md` 의 ERD 절을 다시 그린다.

    python docs/tools/erd_from_migrations.py          # 갱신한다
    python docs/tools/erd_from_migrations.py --check   # 갱신이 필요하면 1 로 죽는다

`docs/data-model.md` 의 아래 두 표지 사이만 갈아 끼운다. 표지 밖은 손대지 않는다.

    <!-- ERD:BEGIN 자동 생성 -->
    <!-- ERD:END -->

⛔ 표를 손으로 적지 마라. 정본은 `src/main/resources/db/migration/*.sql` 이고
   이 스크립트가 그것을 읽는다. 문서와 DB 가 갈리는 자리를 없애려고 만든 것이다.

내보내기 전에 `self_check` 가 mermaid erDiagram 문법을 본다. 어긋나면 **문서를 건드리지
않고 2 로 죽는다** — 안 그려지는 그림을 커밋하는 것보다 낫다.
"""
import glob
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MIGRATION_DIR = os.path.join(ROOT, 'src', 'main', 'resources', 'db', 'migration')
TARGET = os.path.join(ROOT, 'docs', 'data-model.md')
BEGIN = '<!-- ERD:BEGIN 자동 생성 -->'
END = '<!-- ERD:END -->'

# 표를 어느 묶음으로 그리나. 위에서부터 먼저 잡은 묶음이 이긴다.
# 여기 없는 표는 「그 밖」으로 떨어지고 그때 이 목록을 고치라고 알린다.
GROUPS = [
    ('기반 — 사람과 프로젝트', [
        'adk_builder_account', 'adk_builder_claude_credential', 'adk_builder_project',
        'adk_builder_project_facet', 'adk_builder_project_system',
        'adk_builder_repository_update', 'adk_builder_dev_issue_target',
        'adk_builder_design_system_curation',
        'adk_builder_screen_id_group', 'adk_builder_screen_standard_id',
    ]),
    ('FRD — 작업 단위', [
        'adk_builder_frd', 'adk_builder_frd_item', 'adk_builder_frd_facet',
        'adk_builder_frd_analysis_note', 'adk_builder_frd_backend_change',
        'adk_builder_frd_interview_message',
    ]),
    ('FRD 화면 작업', [
        'adk_builder_frd_screen', 'adk_builder_frd_screen_history',
        'adk_builder_frd_screen_ia_placement', 'adk_builder_frd_screen_chat_message',
        'adk_builder_frd_screen_marker', 'adk_builder_frd_screen_marker_history',
        'adk_builder_frd_screen_memo_comment',
    ]),
    ('개발요청서와 전송', [
        'adk_builder_dev_request', 'adk_builder_dev_request_delivery',
    ]),
    ('메뉴구조도 (IA)', [
        'adk_builder_ia_structure', 'adk_builder_ia_row', 'adk_builder_ia_revision',
        'adk_builder_ia_screen_profile',
    ]),
    ('AI 실행', ['adk_builder_ai_run']),
    # 그린존(2026-08-27 개방)에서 빌더가 직접 만드는 산출물. A3 화면설계서가 나중에 여기 붙는다.
    ('그린존 — 빌더가 만드는 산출물', ['adk_builder_user_manual']),
    ('⛔ 폐기된 앞단 — 표만 남아 있다', [
        'adk_builder_intake', 'adk_builder_intake_facet', 'adk_builder_received_document',
        'adk_builder_document_processing_run', 'adk_builder_requirement',
        'adk_builder_mockup_mismatch',
    ]),
]


# ───────────────────────────────────────────── 마이그레이션 재생

def version(path):
    m = re.search(r'V(\d+)__', os.path.basename(path))
    if m is None:
        raise SystemExit('마이그레이션 파일 이름이 V<숫자>__ 꼴이 아니다: ' + path)
    return int(m.group(1))


def strip_line_comments(sql):
    out = []
    for line in sql.split('\n'):
        i = line.find('--')
        if i >= 0 and line[:i].count("'") % 2 == 0:
            line = line[:i]
        out.append(line)
    return '\n'.join(out)


def split_top_level(body):
    parts, depth, cur, inq = [], 0, '', False
    for ch in body:
        if ch == "'":
            inq = not inq
        if not inq:
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
            elif ch == ',' and depth == 0:
                parts.append(cur.strip())
                cur = ''
                continue
        cur += ch
    if cur.strip():
        parts.append(cur.strip())
    return parts


def bare(name):
    return name.split('.')[-1].strip('"')


def read_schema():
    tables = {}
    for path in sorted(glob.glob(os.path.join(MIGRATION_DIR, '*.sql')), key=version):
        src = 'V%d' % version(path)
        sql = strip_line_comments(io.open(path, encoding='utf-8').read())

        for m in re.finditer(r'create\s+table\s+(?:if\s+not\s+exists\s+)?([\w.\"]+)\s*\((.*?)\n\s*\)\s*;',
                             sql, re.I | re.S):
            t = {'cols': [], 'comments': {}, 'fks': [], 'uk': [], 'pk': [], 'src': src}
            for part in split_top_level(m.group(2)):
                p = ' '.join(part.split())
                if not p:
                    continue
                low = p.lower()
                if low.startswith(('primary key', 'constraint', 'unique', 'foreign key', 'check')):
                    k = re.search(r'primary key\s*\(([^)]*)\)', p, re.I)
                    if k:
                        t['pk'] += [c.strip() for c in k.group(1).split(',')]
                    k = re.search(r'unique\s*\(([^)]*)\)', p, re.I)
                    if k:
                        t['uk'].append([c.strip() for c in k.group(1).split(',')])
                    k = re.search(r'foreign key\s*\(([^)]*)\).*?references\s+([\w.\"]+)', p, re.I)
                    if k:
                        t['fks'].append((k.group(1).strip(), bare(k.group(2))))
                    continue
                col = bare(p.split()[0])
                rest = p[len(p.split()[0]):].strip()
                tm = re.match(r'(\w+(?:\s*\([^)]*\))?)', rest)
                typ = tm.group(1) if tm else '?'
                if 'primary key' in low:
                    t['pk'].append(col)
                if re.search(r'\bunique\b', low):
                    t['uk'].append([col])
                r = re.search(r'references\s+([\w.\"]+)', p, re.I)
                if r:
                    t['fks'].append((col, bare(r.group(1))))
                t['cols'].append([col, typ, 'not null' in low])
            tables[bare(m.group(1))] = t

        for m in re.finditer(r'alter\s+table\s+(?:if\s+exists\s+)?([\w.\"]+)\s+(.*?);', sql, re.I | re.S):
            name = bare(m.group(1))
            t = tables.get(name)
            if t is None:
                continue
            for action in split_top_level(' '.join(m.group(2).split())):
                a = action.strip()
                low = a.lower()
                g = re.match(r'add\s+column\s+(?:if\s+not\s+exists\s+)?(\w+)\s+'
                             r'(\w+(?:\s*\([^)]*\))?)', a, re.I)
                if g:
                    t['cols'].append([g.group(1), g.group(2).rstrip(','), 'not null' in low])
                    r = re.search(r'references\s+([\w.\"]+)', a, re.I)
                    if r:
                        t['fks'].append((g.group(1), bare(r.group(1))))
                    continue
                g = re.match(r'drop\s+column\s+(?:if\s+exists\s+)?(\w+)', a, re.I)
                if g:
                    t['cols'] = [c for c in t['cols'] if c[0] != g.group(1)]
                    t['comments'].pop(g.group(1), None)
                    continue
                g = re.match(r'rename\s+column\s+(\w+)\s+to\s+(\w+)', a, re.I)
                if g:
                    for c in t['cols']:
                        if c[0] == g.group(1):
                            c[0] = g.group(2)
                    if g.group(1) in t['comments']:
                        t['comments'][g.group(2)] = t['comments'].pop(g.group(1))
                    continue
                g = re.match(r'rename\s+to\s+(\w+)', a, re.I)
                if g:
                    tables[g.group(1)] = tables.pop(name)
                    name, t = g.group(1), tables[g.group(1)]
                    continue
                g = re.match(r'add\s+constraint\s+\w+\s+(.*)', a, re.I)
                if g:
                    k = re.search(r'unique\s*\(([^)]*)\)', g.group(1), re.I)
                    if k:
                        t['uk'].append([c.strip() for c in k.group(1).split(',')])
                    k = re.search(r'foreign key\s*\(([^)]*)\).*?references\s+([\w.\"]+)', g.group(1), re.I)
                    if k:
                        t['fks'].append((k.group(1).strip(), bare(k.group(2))))
                g = re.match(r'alter\s+column\s+(\w+)\s+.*type\s+(\S+)', a, re.I)
                if g:
                    for c in t['cols']:
                        if c[0] == g.group(1):
                            c[1] = g.group(2)

        for m in re.finditer(r'drop\s+table\s+(?:if\s+exists\s+)?([\w.\"]+)', sql, re.I):
            tables.pop(bare(m.group(1)), None)

        for m in re.finditer(r"comment\s+on\s+column\s+([\w.\"]+)\.(\w+)\s+is\s+'((?:[^']|'')*)'",
                             sql, re.I | re.S):
            name = bare(m.group(1))
            if name in tables:
                tables[name]['comments'][m.group(2)] = ' '.join(m.group(3).replace("''", "'").split())

        for m in re.finditer(
                r'create\s+unique\s+index\s+(?:if\s+not\s+exists\s+)?\w+\s+on\s+([\w.\"]+)\s*\(([^)]*)\)',
                sql, re.I):
            name = bare(m.group(1))
            if name in tables:
                tables[name]['uk'].append([c.strip() for c in m.group(2).split(',')])

    return tables


# ───────────────────────────────────────────── Mermaid 로 그리기

def short_type(t):
    """mermaid 속성 자리에는 괄호를 두지 않는다."""
    return re.sub(r'\(.*\)', '', t).strip().lower() or '?'


def label(text, limit=78):
    """mermaid 주석 자리에 넣을 수 있게 다듬는다. 따옴표와 개행이 들어가면 도형이 깨진다."""
    text = text.replace('"', "'").replace('\\', '/')
    if len(text) > limit:
        text = text[:limit - 1].rstrip() + '…'
    return text


def render(tables):
    lines = []
    placed = set()

    for title, names in GROUPS:
        members = [n for n in names if n in tables]
        if not members:
            continue
        placed.update(members)
        lines.append('### %s' % title)
        lines.append('')
        lines.append('```mermaid')
        lines.append('erDiagram')

        # 관계선 — 이 묶음 안에서 닫히는 것만 그린다. 밖으로 나가는 것은 아래 표로 적는다.
        seen, outward = set(), {}
        for name in members:
            for col, target in tables[name]['fks']:
                if target == name:
                    continue
                if target in members:
                    key = (target, name, col)
                    if key in seen:
                        continue
                    seen.add(key)
                    child = tables[name]
                    optional = not next((c[2] for c in child['cols'] if c[0] == col), False)
                    # 자식의 기본키가 이 외래키 하나뿐이면 부모 한 줄에 자식 한 줄이다
                    one_to_one = child['pk'] == [col]
                    if one_to_one:
                        link = '||--o|' if optional else '||--||'
                    else:
                        link = '||--o{' if optional else '||--|{'
                    lines.append('    %s %s %s : "%s"' % (target, link, name, col))
                else:
                    outward.setdefault(name, []).append((col, target))

        for name in members:
            t = tables[name]
            lines.append('    %s {' % name)
            for col, typ, notnull in t['cols']:
                marks = []
                if col in t['pk']:
                    marks.append('PK')
                if any(col == u[0] and len(u) == 1 for u in t['uk']):
                    marks.append('UK')
                if any(col == c for c, _ in t['fks']):
                    marks.append('FK')
                note = t['comments'].get(col, '')
                # 기본키는 널이 될 수 없다 — not null 을 따로 안 적어도 그렇다
                nullable = not notnull and col not in t['pk']
                if nullable:
                    note = ('널 허용 · ' + note) if note else '널 허용'
                head = '%s %s' % (short_type(typ), col)
                if marks:
                    # mermaid 는 키 여럿을 쉼표로 받는다. 공백으로 붙이면 파싱이 깨진다.
                    head += ' ' + ', '.join(marks)
                if note:
                    # 빈 주석("")도 파서가 싫어한다 — 뜻이 없으면 아예 적지 않는다.
                    head += ' "%s"' % label(note)
                lines.append('        ' + head)
            lines.append('    }')
        lines.append('```')
        lines.append('')

        gaps = []
        for name in members:
            t = tables[name]
            missing = [c[0] for c in t['cols'] if c[0] not in t['comments']]
            if missing:
                gaps.append((name, missing))
        if gaps:
            lines.append('⚠ **뜻이 DB 에 안 적힌 열** — `COMMENT ON COLUMN` 이 없어 위 그림에도 설명이 없다. '
                         '§0 규칙(「열은 영문 · 뜻은 한글 COMMENT」)을 못 지킨 자리다.')
            lines.append('')
            for name, missing in gaps:
                lines.append('- `%s` — %d개: %s' % (
                    name, len(missing), ', '.join('`%s`' % c for c in missing)))
            lines.append('')

        if outward:
            lines.append('이 묶음에서 밖으로 나가는 외래키:')
            lines.append('')
            for name in sorted(outward):
                for col, target in outward[name]:
                    lines.append('- `%s.%s` → `%s`' % (name, col, target))
            lines.append('')

    leftover = sorted(set(tables) - placed)
    if leftover:
        lines.append('### ⚠ 묶음이 정해지지 않은 표')
        lines.append('')
        lines.append('아래 표가 생겼는데 `docs/tools/erd_from_migrations.py` 의 `GROUPS` 에 없다. '
                     '어느 묶음인지 정해서 그 목록에 넣어라.')
        lines.append('')
        for n in leftover:
            lines.append('- `%s` (%s)' % (n, tables[n]['src']))
        lines.append('')

    return '\n'.join(lines).rstrip() + '\n'


# ───────────────────────────────────────────── 자기검사

# mermaid erDiagram 문법. 실제 파서(@mermaid-js/mermaid-cli 11)에 대고 맞춰 놓은 것이다.
#   관계선  ENTITY <카디널리티> ENTITY : "라벨"
#   속성    <타입> <이름> [키[, 키]…] ["주석"]
# ⛔ 키 여럿을 공백으로 붙이면 파서가 죽는다 — 쉼표다.
#    2026-08-27 에 `varchar account_id PK FK` 로 내보내 8블록 전부가 안 그려졌다.
RE_REL = re.compile(r'^ {4}\w+ \|\|--(?:o\{|\|\{|o\||\|\|) \w+ : "[^"]*"$')
RE_OPEN = re.compile(r'^ {4}(\w+) \{$')
RE_CLOSE = re.compile(r'^ {4}\}$')
RE_ATTR = re.compile(r'^ {8}[a-z][a-z0-9_]* [a-z_][a-z0-9_]*'
                     r'(?: (?:PK|FK|UK)(?:, (?:PK|FK|UK))*)?'
                     r'(?: "[^"]*")?$')


def self_check(text):
    """내보낼 mermaid 가 문법에 맞나 본다. 어긋나면 쓰지 않고 죽는다."""
    problems = []
    for bi, block in enumerate(re.findall(r'```mermaid\n(.*?)```', text, re.S), 1):
        lines = block.rstrip('\n').split('\n')
        if lines[0] != 'erDiagram':
            problems.append('블록%d: 첫 줄이 erDiagram 이 아니다: %r' % (bi, lines[0]))
            continue
        depth, defined, used = 0, set(), []
        for ln in lines[1:]:
            if not ln.strip():
                continue
            if depth == 0:
                if RE_REL.match(ln):
                    m = re.match(r'^ {4}(\w+) \S+ (\w+) :', ln)
                    if m:
                        used += [m.group(1), m.group(2)]
                    continue
                m = RE_OPEN.match(ln)
                if m:
                    depth = 1
                    defined.add(m.group(1))
                    continue
                problems.append('블록%d: 최상위에서 못 읽는 줄: %r' % (bi, ln))
            else:
                if RE_CLOSE.match(ln):
                    depth = 0
                elif not RE_ATTR.match(ln):
                    problems.append('블록%d: 속성 줄이 문법에 안 맞다: %r' % (bi, ln))
        if depth:
            problems.append('블록%d: 엔티티 괄호가 닫히지 않았다' % bi)
        for name in used:
            if name not in defined:
                problems.append('블록%d: 관계선의 %s 가 이 블록에 없다' % (bi, name))
    return problems


def main():
    tables = read_schema()
    latest = max(version(p) for p in glob.glob(os.path.join(MIGRATION_DIR, '*.sql')))
    header = (
        '> **이 절은 `docs/tools/erd_from_migrations.py` 가 만든다. 손으로 고치지 마라.**\n'
        '> 정본은 `src/main/resources/db/migration/*.sql` 이고 스크립트가 그것을 재생한다.\n'
        '> 다시 그리기: `python docs/tools/erd_from_migrations.py`\n'
        '>\n'
        '> 지금 기준 — 마이그레이션 **V%d** · 표 **%d개** · 열 **%d개** '
        '(그중 **%d개**에 한글 `COMMENT` 가 있다).\n'
        '> 열 뒤의 `"..."` 는 DB 의 `COMMENT` 를 그대로 옮긴 것이다. 빈 것은 DB 에 뜻이 안 적힌 열이다.\n'
        '> **PK** 기본키 · **FK** 외래키 · **UK** 유니크.\n'
        '> 관계선 — `||--|{` 여럿(필수) · `||--o{` 여럿(널 허용) · `||--||` 하나(필수) · `||--o|` 하나(널 허용).\n'
    ) % (latest, len(tables), sum(len(t['cols']) for t in tables.values()),
         sum(len(t['comments']) for t in tables.values()))

    body = BEGIN + '\n\n' + header + '\n' + render(tables) + '\n' + END

    problems = self_check(body)
    if problems:
        print('⛔ 만든 mermaid 가 문법에 안 맞다 — 문서를 건드리지 않고 멈춘다.')
        for x in problems[:20]:
            print('   ✘', x)
        return 2

    doc = io.open(TARGET, encoding='utf-8').read()
    if BEGIN not in doc or END not in doc:
        sys.exit('표지가 없다: %s 에 %s / %s 를 두고 다시 돌려라.' % (TARGET, BEGIN, END))
    head, rest = doc.split(BEGIN, 1)
    _, tail = rest.split(END, 1)
    updated = head + body + tail

    if updated == doc:
        print('ERD 는 이미 최신이다 (V%d · 표 %d개).' % (latest, len(tables)))
        return 0
    if '--check' in sys.argv:
        print('ERD 가 낡았다. `python docs/tools/erd_from_migrations.py` 를 돌려라.')
        return 1
    io.open(TARGET, 'w', encoding='utf-8', newline='\n').write(updated)
    print('ERD 를 다시 그렸다 (V%d · 표 %d개 · 열 %d개).'
          % (latest, len(tables), sum(len(t['cols']) for t in tables.values())))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
