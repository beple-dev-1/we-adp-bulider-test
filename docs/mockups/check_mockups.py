"""Validate the user-visible contract of the static product mockups."""

from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parent
PRODUCT_PAGES = [
    "00-project-management.html",
    "00a-project-register.html",
    "00b-user-management.html",
    "00c-user-register.html",
    "00d-project-detail.html",
    "00e-project-failure-detail.html",
    "00f-user-detail.html",
    "01-received-docs.html",
    "01-received-docs-empty.html",
    "01a-received-document-register.html",
    "01b-received-document-edit.html",
    "01d-received-document-processing.html",
    "02-requirements.html",
    "02a-requirement-detail.html",
    "03-definitions.html",
    "03a-definition-detail.html",
    "04-brd.html",
    "04a-brd-detail.html",
    "05-frds.html",
    "05a-frd-workbench.html",
    "05b-frd-wizard.html",
    "05n-frd-interview-chat-focus.html",
    "05o-frd-interview-evidence.html",
    "05p-frd-interview-guided.html",
    "05q-frd-canvas.html",
    "06-dev-requests.html",
    "06a-dev-request-detail.html",
    "07-menu-tree.html",
    "07a-menu-tree-workbench.html",
    "08-solution-mockups.html",
    "08a-solution-mockup-detail.html",
    "10-other.html",
]
FORBIDDEN_COPY = (
    "올린다",
    "연다",
    "고친다",
    "나간다",
    "낳는다",
    "역류",
    "기획 레포",
    "추출기 스킬",
    "as-is",
)


class ContractParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.elements: list[tuple[str, dict[str, str]]] = []
        self.text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.elements.append((tag, {key: value or "" for key, value in attrs}))

    def handle_data(self, data: str) -> None:
        self.text.append(data)


def parse(path: Path) -> tuple[ContractParser, str]:
    source = path.read_text(encoding="utf-8")
    parser = ContractParser()
    parser.feed(source)
    return parser, source


def has_class(attrs: dict[str, str], name: str) -> bool:
    return name in attrs.get("class", "").split()


def validate_page(path: Path) -> list[str]:
    parser, source = parse(path)
    visible_text = " ".join(parser.text)
    errors: list[str] = []

    if not any(tag == "template" and attrs.get("id") == "본문" for tag, attrs in parser.elements):
        errors.append("공통 셸에 전달할 <template id=\"본문\">이 없습니다")
    if "_shell.js" not in source:
        errors.append("공통 셸을 불러오지 않습니다")
    if any(has_class(attrs, "mock-note") for _, attrs in parser.elements):
        errors.append("제품 화면에 보이는 설계 메모(mock-note)가 있습니다")
    for phrase in FORBIDDEN_COPY:
        if phrase.casefold() in visible_text.casefold():
            errors.append(f"금지 문구가 있습니다: {phrase}")

    return errors


def validate_received_docs() -> list[str]:
    parser, source = parse(ROOT / "01-received-docs.html")
    errors: list[str] = []
    if "01a-received-document-register.html" not in source:
        errors.append("문서 등록 화면 연결이 없습니다")
    if "01b-received-document-edit.html" not in source:
        errors.append("문서 상세 화면 연결이 없습니다")
    if "01d-received-document-processing.html" not in source:
        errors.append("내용 분석 중 화면 연결이 없습니다")
    if "<dialog" in source:
        errors.append("복잡한 등록·수정 폼이 목록 레이어에 남아 있습니다")

    # 2026-08-15 — 「AI 가 늘 정리한다」와 「처리 방향」이 개념째 폐기됐다.
    # ⛔ 되살아나면 여기가 빨개진다. 목록이 다시 요구사항 대상 여부를 묻게 두지 않는다.
    for retired in ("처리 구분", "처리 대기", "요구사항 대상", "참고 문서",
                    "내용 정리 중", "정리 내용 확인", "처리 방향 선택", "미생성"):
        if retired in source:
            errors.append(f"폐기한 처리 방향·정리 개념이 남아 있습니다: {retired}")
    for status in ("내용 분석 대기", "내용 분석 중", "등록 완료", "문서 처리 오류"):
        if status not in source:
            errors.append(f"문서 상태 값이 없습니다: {status}")
    for requirement_state in ("요구사항 미분석", "요구사항 분석 중", "요구사항 검토 필요", "요구사항 분석 오류", "검토 필요"):
        if requirement_state in source:
            errors.append(f"받은 문서 목록에 요구사항 상태가 노출됩니다: {requirement_state}")
    if "생성된 요구사항" not in source or "0건" not in source:
        errors.append("받은 문서 목록에 생성된 요구사항 건수가 없습니다")
    if "필요한 문서에서 요구사항을 분석합니다" not in source:
        errors.append("목록 부제가 AI 가 늘 정리한다는 전제를 버리지 않았습니다")

    empty_source = (ROOT / "01-received-docs-empty.html").read_text(encoding="utf-8")
    if "조회된 내용이 없습니다." not in empty_source:
        errors.append("목록 공통 빈 결과 문구가 없습니다")
    if "AI가 원문을 먼저 정리" in empty_source:
        errors.append("빈 상태가 아직 AI 가 늘 정리한다고 말합니다")
    return errors


def validate_registration() -> list[str]:
    path = ROOT / "01a-received-document-register.html"
    if not path.exists():
        return ["문서 등록 화면이 없습니다"]

    parser, source = parse(path)
    errors: list[str] = []
    file_inputs = [attrs for tag, attrs in parser.elements if tag == "input" and attrs.get("type") == "file"]
    if len(file_inputs) != 1 or "multiple" in file_inputs[0]:
        errors.append("문서 등록의 첨부파일은 최대 1개여야 합니다")
    elif "required" in file_inputs[0]:
        errors.append("문서 등록의 첨부파일은 선택이어야 합니다")
    if not any(tag == "textarea" and attrs.get("name") == "content" for tag, attrs in parser.elements):
        errors.append("직접 입력할 문서 내용이 없습니다")
    if not any(tag == "select" and attrs.get("name") == "documentType"
               for tag, attrs in parser.elements):
        errors.append("필수 문서 종류 선택이 없습니다")
    if not any(tag == "form" for tag, _ in parser.elements):
        errors.append("문서 등록 폼이 없습니다")
    if "01-received-docs.html" not in source:
        errors.append("등록 화면에서 목록으로 돌아가는 링크가 없습니다")
    if ">목록으로</a>" not in source:
        errors.append("등록 화면의 목록 이동 문구가 상세 화면과 다릅니다")
    for marker, label in (("data-manual-meta", "문서명"), ("data-manual-source", "원문 입력")):
        if not any(marker in attrs and "hidden" in attrs for _, attrs in parser.elements):
            errors.append(f"문서 종류를 고르기 전에 {label} 영역이 노출됩니다")
    if not any(tag == "fieldset" and "document-register-facets" in attrs.get("class", "")
               and "hidden" in attrs for tag, attrs in parser.elements):
        errors.append("문서 종류를 고르기 전에 적용 구분 영역이 노출됩니다")
    return errors


def validate_document_detail() -> list[str]:
    """기본 상세는 한 칸이다 (2026-08-15).

    ⛔ 좌우 대조가 기본으로 돌아오면 여기가 빨개진다 — 대조할 것이 있는 것은
       멀티모달이 읽어 낸 문서뿐이고, 그것은 01c 의 몫이다.
    """
    path = ROOT / "01b-received-document-edit.html"
    if not path.exists():
        return ["문서 상세 화면이 없습니다"]

    parser, source = parse(path)
    errors: list[str] = []
    if "document-compare-workspace--single" not in source:
        errors.append("기본 상세가 한 칸짜리 문서 내용이 아닙니다")
    if "회의 일시" not in source or "참석자" not in source:
        errors.append("문서 정보에 회의 일시·참석자가 없습니다")
    if any(tag == "button" and attrs.get("id") == "analyze-requirements"
           for tag, attrs in parser.elements):
        errors.append("기존 요구사항이 있는데 요구사항 분석 버튼이 열려 있습니다")
    if not any(tag == "button" and attrs.get("id") == "delete-generated-requirements"
               for tag, attrs in parser.elements):
        errors.append("기존 요구사항을 삭제하고 다시 분석하는 길이 없습니다")
    if "다시 분석하려면 기존 요구사항을 삭제해야 합니다" not in source:
        errors.append("기존 요구사항이 있을 때 다시 분석하는 방법을 안내하지 않습니다")
    # ⛔ 이 삭제는 제외가 아니라 줄을 지우는 것이다 — 사람이 확정하거나 손으로 고친 것까지 함께 간다.
    if "확정하거나 직접 고친 내용도 함께 사라지며" not in source:
        errors.append("요구사항 삭제가 확정·직접 수정한 내용까지 지운다는 것을 알리지 않습니다")
    if "requirement-summary" not in source or "생성된 요구사항" not in source or "1건" not in source:
        errors.append("상세에서 이 문서로 생성된 요구사항 건수를 확인할 수 없습니다")
    for requirement_detail in ("REQ-001", "REQ-002", "요구사항 검토 필요", "검토 필요"):
        if requirement_detail in source:
            errors.append(f"받은 문서 상세에 요구사항 상태·내용이 노출됩니다: {requirement_detail}")
    for retired in ("AI 1차 정리", "AI 정리본", "원문 대조", "등록 원문",
                    "원문으로 다시 정리", "정리 내용 확인", "process-choice",
                    "요구사항 대상", "참고 문서"):
        if retired in source:
            errors.append(f"폐기한 정리·처리 방향 개념이 남아 있습니다: {retired}")
    return errors


def validate_definition_detail() -> list[str]:
    path = ROOT / "03a-definition-detail.html"
    if not path.exists():
        return ["요구사항정의서 상세 화면이 없습니다"]

    parser, source = parse(path)
    errors: list[str] = []
    if "현재 정의" not in source or "출처 요구사항" not in source:
        errors.append("정의서 내용과 출처 요구사항을 함께 확인할 수 없습니다")
    if "같은 요구사항에서 분리된 정의서" not in source:
        errors.append("같은 요구사항에서 분리된 다른 정의서와 경계를 확인할 수 없습니다")
    for label in ("적용 조건", "기대 결과", "포함 범위", "제외 범위"):
        if label not in source:
            errors.append(f"단일 요건 검토에 필요한 항목이 없습니다: {label}")
    definition_chat_source = (ROOT / "_definition-concepts.js").read_text(encoding="utf-8")
    if 'id="definition-claude-message"' not in definition_chat_source:
        errors.append("정의서를 구체화할 AI 대화 입력 영역이 없습니다")
    if "변경 내용 저장" not in source:
        errors.append("AI 대화로 정리한 정의서를 저장하는 행동이 없습니다")
    if "정의서 폐기" not in source or 'id="retire-reason"' not in source:
        errors.append("사람이 사유를 입력하고 정의서를 폐기하는 행동이 없습니다")
    if "번호와 변경 이력은 유지됩니다" not in source:
        errors.append("정의서 폐기 뒤 번호와 이력이 유지됨을 안내하지 않습니다")
    if "requirement-chat" in source:
        errors.append("요구사항정의서 상세에 본문 폭을 차지하는 AI 열이 남아 있습니다")
    for asset in ("_definition-concepts.css", "_definition-concepts.js"):
        if asset not in source:
            errors.append(f"요구사항정의서 상세가 확정된 공통 자산을 사용하지 않습니다: {asset}")
    return errors


def validate_requirements_navigation() -> list[str]:
    list_path = ROOT / "02-requirements.html"
    detail_path = ROOT / "02a-requirement-detail.html"
    list_source = list_path.read_text(encoding="utf-8")
    detail_parser, detail_source = parse(detail_path)
    errors: list[str] = []
    if "다음 할 일" in list_source:
        errors.append("요구사항 목록에 다음 할 일 열이 남아 있습니다")
    if '<th scope="col">요구 사항</th>' not in list_source:
        errors.append("요구사항 목록의 요구 사항 열이 없습니다")
    if list_source.count('href="02a-requirement-detail.html"') < 4:
        errors.append("요구 사항을 눌러 상세로 이동할 수 없습니다")
    if not any(tag == "button" and attrs.get("id") == "request-definition" for tag, attrs in detail_parser.elements):
        errors.append("요구사항 상세에 정의서 생성 요청 버튼이 없습니다")
    if "요구사항을 확정한 뒤 요구사항정의서 생성을 요청" not in detail_source:
        errors.append("요구사항 확정 뒤 정의서 생성을 요청하는 순서가 드러나지 않습니다")
    if "requirement-chat" in detail_source or "rq-summary-side" in detail_source:
        errors.append("요구사항 상세에 본문 폭을 차지하는 보조 열이 남아 있습니다")
    for asset in ("_requirement-concepts.css", "_requirement-concepts.js"):
        if asset not in detail_source:
            errors.append(f"요구사항 상세가 확정된 공통 자산을 사용하지 않습니다: {asset}")
    if "관련 화면 후보" not in detail_source or "rq-summary-main" not in detail_source:
        errors.append("관련 화면 후보가 요구사항 상세 본문에 배치되지 않았습니다")
    return errors


def validate_brd_navigation() -> list[str]:
    list_path = ROOT / "04-brd.html"
    detail_path = ROOT / "04a-brd-detail.html"
    list_source = list_path.read_text(encoding="utf-8")
    detail_parser, detail_source = parse(detail_path)
    errors: list[str] = []
    if "전체 12" in list_source or "상태 · 다음 작업" in list_source or "마지막 저장" in list_source:
        errors.append("BRD 목록에 제거하기로 한 요약이나 작업 정보가 남아 있습니다")
    for status in ("작업 대기", "작업중", "재작업 필요", "작업 완료"):
        if status not in list_source:
            errors.append(f"BRD 작업 상태가 없습니다: {status}")
    for status in ("안 열림", "다시 열림", "넘김"):
        if status in list_source:
            errors.append(f"BRD 목록에 폐기한 상태가 남아 있습니다: {status}")
    if list_source.count('href="04a-brd-detail.html"') < 4:
        errors.append("BRD 업무를 눌러 상세로 이동할 수 없습니다")
    for label in ("포함 요구사항정의서", "변경 대상 화면", "화면 외 구현 요건", "메뉴구조 변경"):
        if label not in detail_source:
            errors.append(f"BRD 상세의 작업 구성 항목이 없습니다: {label}")
    for label in ("wv-appr-write", "연결된 정의서", "변경 내용"):
        if label not in detail_source:
            errors.append(f"BRD 상세의 화면별 초벌 정보가 없습니다: {label}")
    if not any(tag == "button" and attrs.get("id") == "start-brd" for tag, attrs in detail_parser.elements):
        errors.append("BRD 초안 검토 뒤 작업을 시작할 버튼이 없습니다")
    brd_chat_source = (ROOT / "_brd-concepts.js").read_text(encoding="utf-8")
    if 'id="brd-claude-message"' not in brd_chat_source:
        errors.append("BRD 초안을 수정할 AI 대화 입력 영역이 없습니다")
    if "변경안 적용" in detail_source:
        errors.append("BRD AI 대화에 변경안 적용 영역이 남아 있습니다")
    if "requirement-chat" in detail_source:
        errors.append("BRD 상세에 본문 폭을 차지하는 AI 열이 남아 있습니다")
    for asset in ("_brd-concepts.css", "_brd-concepts.js"):
        if asset not in detail_source:
            errors.append(f"BRD 상세가 확정된 공통 자산을 사용하지 않습니다: {asset}")
    return errors


def check_frd_naming(path: Path, html: str) -> list[str]:
    """FRD 로 개명한 뒤 옛 말이 되살아나면 빨개진다 (계획 8 · 2026-08-18).

    ⚠ 05* 화면 파일뿐 아니라 _frd-concepts.js 같은 밑줄 자산도 본다(2026-08-18 최종 리뷰) —
       startswith("05") 만으로는 05 로 시작하지 않는 공용 자산이 검사망을 빠져나간다.
    """
    problems = []
    if path.name.startswith("05") or path.name.startswith("_frd-concepts"):
        if "작업 목업" in html:
            problems.append(f"{path.name}: 「작업 목업」은 폐기된 말이다 — FRD 로 적는다")
        if "MOCK-" in html:
            problems.append(f"{path.name}: MOCK- 접두사는 폐기됐다 — FRD- 하나뿐이다")
        if path.name.startswith("05a") and "BRD-" in html:
            problems.append(f"{path.name}: 작업대 머리는 FRD- 다. BRD 는 출처 칸에만 온다")
    return problems


def _frd_table_body(html: str) -> str | None:
    """05-frds.html 의 <tbody> 안쪽만 떼어 낸다 — 거르개 <option> 이 라벨을 이미 다 갖고 있어서
    파일 전체를 재면 표의 줄이 상태를 하나도 안 보여줘도 통과해 버린다 (2026-08-18 리뷰 지적)."""
    match = re.search(r"<tbody>(.*?)</tbody>", html, re.S)
    return match.group(1) if match else None


def check_frd_states(path: Path, html: str) -> list[str]:
    """목록이 상태 일곱을 다 보여줘야 한다 — 인터뷰 대기와 분석 결과 확인을 구별한다."""
    if path.name != "05-frds.html":
        return []
    body = _frd_table_body(html)
    if body is None:
        return ["05-frds.html: 표 본체(<tbody>)를 못 찾았다"]
    missing = [label for label in ("요구사항 분석 중", "답변 필요", "분석 오류", "분석 결과 확인",
                                   "수정 중", "완료") if label not in body]
    return [f"05-frds.html: 상태 라벨이 빠졌다 — {', '.join(missing)}"] if missing else []


def check_frd_no_screen_row(path: Path, html: str) -> list[str]:
    """화면 없는 FRD 가 목록에 앉는 것이 이 개편의 핵심이다."""
    if path.name != "05-frds.html":
        return []
    body = _frd_table_body(html)
    if body is None:
        return ["05-frds.html: 표 본체(<tbody>)를 못 찾았다"]
    return [] if "프론트 없음" in body else ["05-frds.html: 화면 없는 FRD 줄이 없다"]


def check_frd_wizard_exits(path: Path, html: str) -> list[str]:
    """마법사에 안전장치 셋이 다 있어야 한다."""
    if path.name != "05b-frd-wizard.html":
        return []
    missing = [label for label in ("화면 직접 고르기", "새 화면 만들기", "화면 없는 요건입니다")
               if label not in html]
    return [f"05b-frd-wizard.html: 마법사 출구가 빠졌다 — {', '.join(missing)}"] if missing else []


def validate_frd_screens() -> list[str]:
    list_path = ROOT / "05-frds.html"
    workbench_path = ROOT / "05a-frd-workbench.html"
    list_source = list_path.read_text(encoding="utf-8")
    workbench_parser, workbench_source = parse(workbench_path)
    errors: list[str] = []
    if "전체 28" in list_source or "현재 버전" in list_source or "최종 수정" in list_source or '<th scope="col">작업</th>' in list_source:
        errors.append("FRD 작업 목록에 화면 단위 목록 정보가 남아 있습니다")
    if list_source.count('href="05a-frd-workbench.html"') < 3:
        errors.append("FRD 를 눌러 작업대로 이동할 수 없습니다")
    for label in ("FRD", "요구사항", "적용 대상", "시스템", "작업 범위", "상태",
                  "담당자", "생성일시", "완료일시"):
        if label not in list_source:
            errors.append(f"FRD 작업 목록의 판단 항목이 없습니다: {label}")
    if '<th scope="col">BRD</th>' in list_source:
        errors.append("FRD 작업 목록에 BRD 컬럼이 남아 있습니다")
    for label in ("FRD 화면", "FRD 화면 저장"):
        if label not in workbench_source:
            errors.append(f"FRD 작업대의 필수 항목이 없습니다: {label}")
    for hidden_internal_copy in ("목업 검사 통과", "방금 실행 되돌리기", "BRD 목차", "기타 작업 3건"):
        if hidden_internal_copy in workbench_source:
            errors.append(f"기획자 화면에 내부 검증 용어가 노출됩니다: {hidden_internal_copy}")
    if "요구사항" not in workbench_source:
        errors.append("요구사항이 상단 정보 영역에 요약되지 않았습니다")
    for screen_control in ('id="work-screen-list"', 'id="add-work-screen"', 'id="add-work-screen-dialog"'):
        if screen_control not in workbench_source:
            errors.append(f"작업 화면이 많을 때 관리할 수 있는 기능이 없습니다: {screen_control}")
    for canvas_control in ('id="mockup-canvas"', 'id="zoom-out"', 'id="zoom-reset"', 'id="zoom-in"'):
        if canvas_control not in workbench_source:
            errors.append(f"목업 캔버스 기능이 없습니다: {canvas_control}")
    mockup_chat_source = (ROOT / "_frd-concepts.js").read_text(encoding="utf-8")
    if 'id="work-mockup-claude-message"' not in mockup_chat_source:
        errors.append("FRD 작업을 수정할 AI 대화 입력 영역이 없습니다")
    if not any(tag == "button" and attrs.get("id") == "select-mockup-area" for tag, attrs in workbench_parser.elements):
        errors.append("FRD 작업에서 수정할 화면 영역을 선택할 수 없습니다")
    if 'id="mockup-selection-context"' not in mockup_chat_source or "data-mockup-id" not in workbench_source:
        errors.append("선택한 화면 영역을 AI 대화 맥락으로 전달할 수 없습니다")
    if 'id="add-mockup-marker"' not in workbench_source or 'id="mockup-marker-description"' not in workbench_source:
        errors.append("FRD 작업의 화면 위치에 설명 마커를 추가할 수 없습니다")
    for label in ("설명 저장", "설명 삭제", "AI 수정에 사용"):
        if label not in workbench_source:
            errors.append(f"화면 설명 마커의 필수 행동이 없습니다: {label}")
    if "변경안 적용" in workbench_source:
        errors.append("FRD 작업 AI 대화에 변경안 적용 영역이 남아 있습니다")
    if "requirement-chat" in workbench_source:
        errors.append("FRD 작업 상세에 본문 폭을 차지하는 AI 열이 남아 있습니다")
    for asset in ("_frd-concepts.css", "_frd-concepts.js"):
        if asset not in workbench_source:
            errors.append(f"FRD 작업 상세가 확정된 공통 자산을 사용하지 않습니다: {asset}")
    return errors


def validate_menu_ia() -> list[str]:
    list_source = (ROOT / "07-menu-tree.html").read_text(encoding="utf-8")
    candidates = {
        "경로 목록형": (ROOT / "07a-menu-tree-workbench.html").read_text(encoding="utf-8"),
        "계층형": (ROOT / "07b-menu-tree-hierarchy.html").read_text(encoding="utf-8"),
        "C1 연결선형": (ROOT / "07c-menu-tree-split.html").read_text(encoding="utf-8"),
        "C2 가지 묶음형": (ROOT / "07d-menu-tree-cascade.html").read_text(encoding="utf-8"),
        "C3 트리 표형": (ROOT / "07e-menu-tree-groups.html").read_text(encoding="utf-8"),
    }
    errors: list[str] = []
    if 'href="07a-menu-tree-workbench.html"' not in list_source:
        errors.append("시스템 이름을 눌러 메뉴구조도 상세로 이동하는 링크가 없습니다")
    required = {
        "경로 목록형": ("메뉴 경로", "최종 수정일", "뒤로가기",
                    "customer/user/detail/identity"),
        "계층형": ("메뉴 계층", "Depth 1", "본인확인", "bo-user-identity", "뒤로가기"),
        "C1 연결선형": ("메뉴 구조", "뒤로가기", "전체 <span class=\"num\">82</span>개", "메뉴 그룹 추가", "메뉴 삭제",
                    "위로 이동", "아래로 이동", "연결 화면", "메뉴 수정",
                    "미연결 화면", "aria-current=\"page\"", "bo-user-identity", "<dialog", "aria-modal=\"true\""),
        "C2 가지 묶음형": ("메뉴 트리", "뒤로가기", "연결 화면", "메뉴 수정", "Depth 1", "Depth 4"),
        "C3 트리 표형": ("메뉴 트리", "뒤로가기", "연결 화면", "메뉴 수정", "bo-user-identity", "D4"),
    }
    for name, source in candidates.items():
        for label in required[name]:
            if label not in source:
                errors.append(f"메뉴구조도 {name} 시안의 필수 항목이 없습니다: {label}")
        if name != "C1 연결선형" and ("<input" in source or "contenteditable" in source):
            errors.append(f"메뉴구조도 {name} 시안에 상세 화면 직접 편집이 남았습니다")
        if name == "C1 연결선형":
            dialog = source[source.find("<dialog"):source.find("</dialog>")]
            for label in ("메뉴 연결", "메뉴 정보", "메뉴명", "상위 메뉴", "연결 화면", ">추가<"):
                if label not in dialog:
                    errors.append(f"메뉴 추가 레이어 필수 항목이 없습니다: {label}")
            for stale in ('id="row-order"', "경로 식별자", "Depth 1", "사용자 유형", "메뉴 행 저장", "메뉴 유형", "적용 대상", "화면 찾기", "화면 선택", "메뉴 위치 변경", "이동할 메뉴", "이동할 위치"):
                if stale in dialog:
                    errors.append(f"메뉴 추가 레이어에 내부 편집 항목이 남았습니다: {stale}")
        if "메뉴구조도 초기화" in source or "시스템 목록" in source:
            errors.append(f"메뉴구조도 {name} 시안에 제거된 상단 기능이 남아 있습니다")
        if name.startswith("C") and ("<ul>" not in source or source.count("<ul>") < 4):
            errors.append(f"메뉴구조도 {name} 시안이 부모 아래에 자식이 중첩되는 트리 구조가 아닙니다")
        if "작업 상태" in source or "편집 중" in source or "확정 차수" in source:
            errors.append(f"메뉴구조도 {name} 시안에 제거한 작업 상태가 남았습니다")
        navigation = (("07c-menu-tree-split.html", "07d-menu-tree-cascade.html", "07e-menu-tree-groups.html")
                      if name.startswith("C") else
                      ("07a-menu-tree-workbench.html", "07b-menu-tree-hierarchy.html", "07c-menu-tree-split.html"))
        for other in navigation:
            if f'href="{other}"' not in source:
                errors.append(f"메뉴구조도 {name} 시안에서 다른 비교 시안으로 이동할 수 없습니다: {other}")
    return errors


def validate_dev_requests() -> list[str]:
    list_source = (ROOT / "06-dev-requests.html").read_text(encoding="utf-8")
    detail_parser, detail_source = parse(ROOT / "06a-dev-request-detail.html")
    errors: list[str] = []
    # 전달 상태 셋에 사람이 취소한 상태가 별도 축으로 붙는다 (2026-08-25 확정).
    for state in ("대기", "전송중", "전송완료", "취소"):
        if state not in list_source:
            errors.append(f"개발요청서 전달 상태가 목록에 없습니다: {state}")
    for old_state in ("안 보냄", "보내는 중", "보냄", "전달 전", "전달 중", "전달 완료", "전달 실패"):
        if old_state in list_source:
            errors.append(f"설계 정본과 다른 개발 전달 상태가 목록에 남았습니다: {old_state}")
    if 'href="06a-dev-request-detail.html"' not in list_source:
        errors.append("개발요청서 상세 화면으로 이동하는 링크가 없습니다")
    if "다음 작업" in list_source:
        errors.append("개발요청서 목록에 다음 작업 열이 남았습니다")
    # 작업 단위는 FRD 다 (2026-08-20 개정). ⛔ BRD 기준으로 되돌리지 마라 — 구현은 이미 FRD 를 가리킨다.
    for column in ("작업 범위", "기준 FRD", "담당자", "생성일시", "요청일시"):
        if column not in list_source:
            errors.append(f"개발요청서 목록 필수 열이 없습니다: {column}")
    for stale in ("기준 BRD", "BRD-", "04a-brd-detail.html", "최종 변경"):
        if stale in list_source:
            errors.append(f"개발요청서 목록에 BRD 시절 자취가 남았습니다: {stale}")
    if "기준 BRD" in detail_source:
        errors.append("개발요청서 상세가 아직 BRD 를 기준으로 가리킵니다: 기준 BRD")
    for label in ("요청 원문", "요청 내용", "개발 범위", "완료 조건", "확인 필요", "운영 반영", "범위 밖", "화면별 변경 내용", "화면 외 구현", "개발팀 전달사항"):
        if label not in detail_source:
            errors.append(f"개발요청서 상세 필수 영역이 없습니다: {label}")
    for layout_class in ("dev-request-source", "dev-request-primary", "dev-request-implementation", "dev-request-review"):
        if layout_class not in detail_source:
            errors.append(f"개발요청서 상세의 반응형 정보 구조가 없습니다: {layout_class}")
    for development_contract in ("status-badge--progress", "개발 진행 중"):
        if development_contract not in detail_source:
            errors.append(f"전송 완료 후 개발 상태 표시가 없습니다: {development_contract}")
    if "개발요청서 다운로드" not in detail_source:
        errors.append("전송한 개발요청서 ZIP 다운로드 행동이 없습니다")
    # 현재 저장 데이터에는 개발 범위와 화면·백엔드·완료 조건 사이의 직접 연결 정보가 없다.
    for inferred_label in ("관련 화면", "관련 범위", "확인할 내용", "영향", "FRD 전체 조건", "FRD 공통 확인", "분석 메모"):
        if inferred_label in detail_source:
            errors.append(f"저장 데이터로 확인할 수 없는 관계 표현이 남았습니다: {inferred_label}")
    for stale in ("포함 요구사항정의서", "개발팀에 전달", "개발요청서 다시 만들기"):
        if stale in detail_source:
            errors.append(f"현재 제품 흐름에서 지원하지 않는 개발요청서 상세 요소가 남았습니다: {stale}")
    for ambiguous_label in ("업무 규칙과 완료 기준", "관련 자료"):
        if ambiguous_label in detail_source:
            errors.append(f"출처가 불명확하거나 중복된 영역이 남았습니다: {ambiguous_label}")
    if not any(tag == "textarea" and attrs.get("id") == "dev-comment" for tag, attrs in detail_parser.elements):
        errors.append("사람 코멘트 입력 영역이 없습니다")
    for label in ("개발요청", "개발 완료일", "배포일", "첨부파일"):
        if label not in detail_source:
            errors.append(f"개발요청 레이어 필수 요소가 없습니다: {label}")
    if not any(tag == "dialog" and attrs.get("aria-modal") == "true" for tag, attrs in detail_parser.elements):
        errors.append("개발요청 입력이 레이어로 제공되지 않습니다")
    if not any(tag == "input" and attrs.get("type") == "file" for tag, attrs in detail_parser.elements):
        errors.append("개발요청 첨부파일 입력이 없습니다")
    if "dev-request-delivery" in detail_source:
        errors.append("개발팀 전달사항이 상세 본문에 상시 노출되고 있습니다")
    return errors


def validate_solution_mockups() -> list[str]:
    """③ 솔루션 목업 목록·상세.

    ⛔ 2026-08-16 에 계약이 셋 바뀌었다 (계획 7 · 병주 확정). 옛 검사로 되돌리지 마라.
       ① 「버전」은 화면 파일의 git 변경 순서다 — 메뉴구조도 revision과 섞지 않는다
       ② 화면ID 는 기획 레포의 것이다 — SOL-* 꼴 자기 이름은 artifacts.md 가 금지했다(2026-08-13)
       ③ 「읽기 전용」은 없다 — 설계가 「③ 은 고치지 않는다」를 뒤집었다(2026-08-14)
    """
    parser, source = parse(ROOT / "08-solution-mockups.html")
    errors: list[str] = []
    visible_text = " ".join(parser.text)
    for label in ("화면관리번호", "화면 ID", "화면명", "버전", "시스템", "종류", "최초 작성일", "수정일", "최종 수정자"):
        if label not in visible_text:
            errors.append(f"기준 화면의 필수 정보가 없습니다: {label}")
    for implementation_term in ("IA 연결", "DB 직접 연결 없음", "메뉴구조도 연결"):
        if implementation_term in visible_text:
            errors.append(f"기획자 화면에 구현 용어가 남았습니다: {implementation_term}")
    if "수집일" in visible_text:
        errors.append("최초 작성일과 수정일을 대신하던 수집일이 남았습니다")
    if any(tag == "select" and attrs.get("id") == "solution-version" for tag, attrs in parser.elements):
        errors.append("아무것도 못 거르는 버전 필터가 남았습니다")
    if "SOL-" in visible_text:
        errors.append("빌더가 지어낸 화면 이름이 남았습니다: SOL-")
    if not any(tag == "select" and attrs.get("id") == "solution-system" for tag, attrs in parser.elements):
        errors.append("시스템 필터가 없습니다")
    if any(attrs.get("id") == "solution-menu" for _, attrs in parser.elements):
        errors.append("삭제한 상위 메뉴 필터가 남았습니다")
    if not any(tag == "nav" and "list-pagination" in attrs.get("class", "").split() for tag, attrs in parser.elements):
        errors.append("어드민 스타일 페이지네이션이 없습니다")
    if not any(attrs.get("aria-current") == "page" for _, attrs in parser.elements):
        errors.append("페이지네이션에 현재 페이지 표시가 없습니다")
    if not any(tag == "select" and attrs.get("id") == "solution-page-size" for tag, attrs in parser.elements):
        errors.append("목록 크기 설정이 없습니다")
    if not any(tag == "a" and attrs.get("href") == "08a-solution-mockup-detail.html" for tag, attrs in parser.elements):
        errors.append("화면명을 눌러 상세로 이동하는 링크가 없습니다")

    detail_path = ROOT / "08a-solution-mockup-detail.html"
    if not detail_path.exists():
        errors.append("솔루션 목업 상세 화면이 없습니다")
        return errors
    detail_parser, detail_source = parse(detail_path)
    detail_text = " ".join(detail_parser.text)
    for label in ("화면관리번호", "화면 ID", "최초 작성일", "최종 수정", "수정 이력", "수정자", "작업 목업 기준 화면"):
        if label not in detail_text:
            errors.append(f"상세 화면의 기준 정보가 없습니다: {label}")
    for gone in ("읽기 전용", "현재 버전", "버전 이력"):
        if gone in detail_text:
            errors.append(f"뒤집힌 설계의 옛 표시가 상세에 남았습니다: {gone}")
    for gone in ("보정하기", "실물과 다름", "종류", "적용 구분", "IA 연결"):
        if gone in detail_text:
            errors.append(f"상세에 삭제한 기능이 남아 있습니다: {gone}")
    # ⛔ 미리보기는 추출된 진짜 화면이다. 가짜 마크업으로 되돌리지 마라.
    if not any(tag == "iframe" for tag, _ in detail_parser.elements):
        errors.append("추출된 운영 화면을 끼우는 자리가 없습니다")
    if not any(tag == "iframe" and "sandbox" in attrs for tag, attrs in detail_parser.elements):
        errors.append("미리보기에 sandbox 가 없습니다 — 남의 스크립트가 돌 수 있습니다")
    if any(has_class(attrs, "wm-product") for _, attrs in detail_parser.elements):
        errors.append("가짜 미리보기 마크업이 남았습니다: wm-product")
    for control in ("zoom-out", "zoom-reset", "zoom-in"):
        if not any(attrs.get("id") == control for _, attrs in detail_parser.elements):
            errors.append(f"상세 미리보기 확대·축소 기능이 없습니다: {control}")
    if "지금:'solution-mockups'" not in detail_source:
        errors.append("상세 화면의 현재 메뉴가 솔루션 목업이 아닙니다")
    return errors

def validate_list_pagination() -> list[str]:
    list_pages = (
        "01-received-docs.html",
        "02-requirements.html",
        "03-definitions.html",
        "04-brd.html",
        "05-frds.html",
        "06-dev-requests.html",
        "08-solution-mockups.html",
    )
    errors: list[str] = []
    for filename in list_pages:
        parser, source = parse(ROOT / filename)
        if not any(tag == "nav" and "list-pagination" in attrs.get("class", "").split() for tag, attrs in parser.elements):
            errors.append(f"{filename}: 공통 페이지네이션이 없습니다")
        if not any(tag == "select" and attrs.get("id", "").endswith("page-size") for tag, attrs in parser.elements):
            errors.append(f"{filename}: 목록 크기 설정이 없습니다")
        if "list-page-ellipsis" in source:
            errors.append(f"{filename}: 생략 페이지가 남았습니다")
        for page_number in range(1, 11):
            if f'>{page_number}</a>' not in source:
                errors.append(f"{filename}: 페이지 번호 {page_number}이 없습니다")
    guide_source = (ROOT / "_style-guide.html").read_text(encoding="utf-8")
    for class_name in ("list-table-foot", "list-pagination", "list-page-button", "list-page-size"):
        if class_name not in guide_source:
            errors.append(f"스타일 가이드에 페이징 구성요소가 없습니다: {class_name}")
    return errors


def validate_project_management() -> list[str]:
    path = ROOT / "00-project-management.html"
    if not path.exists():
        return ["프로젝트 관리 화면이 없습니다"]

    parser, source = parse(path)
    errors: list[str] = []
    if "00a-project-register.html" not in source:
        errors.append("프로젝트 등록 화면 연결이 없습니다")
    if "00d-project-detail.html" not in source:
        errors.append("프로젝트명을 눌러 상세 화면으로 이동할 수 없습니다")
    project_fields = {"name", "repoUrl", "defaultBranch", "token"}
    if any(tag == "input" and attrs.get("name") in project_fields for tag, attrs in parser.elements):
        errors.append("프로젝트 목록에 등록 입력이 섞여 있습니다")
    visible_text = " ".join(parser.text)
    if "프로젝트 관리" not in visible_text or "저장소" not in visible_text:
        errors.append("프로젝트 관리 목적과 저장소 정보가 드러나지 않습니다")
    for state in ("준비됨", "받는 중", "실패"):
        if state not in visible_text:
            errors.append(f"프로젝트 목록에 상태가 없습니다: {state}")
    if "00e-project-failure-detail.html" not in source:
        errors.append("실패한 프로젝트의 복구 상세 화면 연결이 없습니다")
    failure_source = (ROOT / "00e-project-failure-detail.html").read_text(encoding="utf-8")
    for phrase in ("실패 이유", "저장소 받기 재시도"):
        if phrase not in failure_source:
            errors.append(f"프로젝트 실패 상세에 복구 정보가 없습니다: {phrase}")
    if "관리" in [text.strip() for text in parser.text if text.strip()]:
        errors.append("프로젝트 목록 표에 관리 열이 남았습니다")
    for setting in ("모양:'관리'", "지금:'projects'", "슈퍼계정:true"):
        if setting not in source:
            errors.append(f"프로젝트 관리 셸 호출에 필수 설정이 없습니다: {setting}")
    return errors


def validate_project_registration() -> list[str]:
    path = ROOT / "00a-project-register.html"
    if not path.exists():
        return ["프로젝트 등록 화면이 없습니다"]

    parser, source = parse(path)
    errors: list[str] = []
    project_fields = ("name", "repoUrl", "defaultBranch", "token")
    inputs = {
        attrs.get("name"): attrs
        for tag, attrs in parser.elements
        if tag == "input" and attrs.get("name") in project_fields
    }
    forms = [attrs for tag, attrs in parser.elements if tag == "form"]
    if not forms:
        errors.append("프로젝트 등록 form이 없습니다")
    elif forms[0].get("method", "").lower() != "post" or forms[0].get("onsubmit", "").strip() != "return false":
        errors.append("프로젝트 등록 form이 POST 방식이 아니거나 정적 목업 제출을 막지 않습니다")
    for name in project_fields:
        if "required" not in inputs.get(name, {}):
            errors.append(f"프로젝트 등록 필수 입력이 없습니다: {name}")
    label_targets = {
        attrs.get("for")
        for tag, attrs in parser.elements
        if tag == "label" and attrs.get("for")
    }
    for name in project_fields:
        input_id = inputs.get(name, {}).get("id")
        if not input_id or input_id not in label_targets:
            errors.append(f"프로젝트 등록 label이 입력 항목과 연결되지 않았습니다: {name}")
    if inputs.get("token", {}).get("type") != "password":
        errors.append("GitLab 토큰 입력이 비밀번호 형식이 아닙니다")
    if "00-project-management.html" not in source:
        errors.append("취소 후 프로젝트 목록으로 돌아가는 링크가 없습니다")
    for setting in ("모양:'관리'", "지금:'projects'", "슈퍼계정:true"):
        if setting not in source:
            errors.append(f"프로젝트 등록 셸 호출에 필수 설정이 없습니다: {setting}")
    return errors


def validate_user_management() -> list[str]:
    list_path = ROOT / "00b-user-management.html"
    register_path = ROOT / "00c-user-register.html"
    if not list_path.exists() or not register_path.exists():
        return ["사용자 관리 목록 또는 사용자 등록 화면이 없습니다"]
    list_parser, list_source = parse(list_path)
    register_parser, register_source = parse(register_path)
    errors: list[str] = []
    if 'href="00c-user-register.html"' not in list_source:
        errors.append("사용자 관리에서 새 사용자 등록 화면으로 이동할 수 없습니다")
    if 'href="00f-user-detail.html"' not in list_source:
        errors.append("사용자 관리에서 사용자 상세 화면으로 이동할 수 없습니다")
    if "admin-summary" in list_source:
        errors.append("사용자 관리 상단에 KPI 영역이 남아 있습니다")
    for label in ("로그인 아이디", "이메일", "권한", "Claude 계정"):
        if label not in list_source:
            errors.append(f"사용자 관리 판단 항목이 없습니다: {label}")
    if "최초 설정" in list_source or 'id="user-setup"' in list_source:
        errors.append("사용자 관리 목록에 상세 확인용 최초 설정 항목이 남아 있습니다")
    required_names = {"loginId", "name", "email", "temporaryPassword"}
    inputs = {attrs.get("name"): attrs for tag, attrs in register_parser.elements if tag == "input"}
    for name in required_names:
        if "required" not in inputs.get(name, {}):
            errors.append(f"사용자 등록 필수 입력이 없습니다: {name}")
    if inputs.get("temporaryPassword", {}).get("type") != "password":
        errors.append("임시 비밀번호 입력이 비밀번호 형식이 아닙니다")
    if 'href="00b-user-management.html"' not in register_source:
        errors.append("사용자 등록 취소 후 목록으로 돌아갈 수 없습니다")
    if 'name="role"' not in register_source:
        errors.append("사용자 등록 화면에서 권한을 선택할 수 없습니다")
    for role_value in ('value="PLANNER"', 'value="SUPER"'):
        if role_value not in register_source:
            errors.append(f"사용자 등록 권한 선택값이 없습니다: {role_value}")
    for state in ("미연결", "연결 완료"):
        if state not in list_source:
            errors.append(f"사용자 관리 Claude 계정 상태가 없습니다: {state}")
    if not re.search(r"슈퍼 관리자.*?미연결", list_source, re.S):
        errors.append("슈퍼관리자의 Claude 계정이 미연결 상태로 표시되지 않습니다")
    if "연결 전" in list_source or "해당 없음" in list_source or ">—<" in list_source:
        errors.append("사용자 관리에 폐기한 Claude 계정 상태 문구가 남아 있습니다")
    return errors


def validate_auth_flow() -> list[str]:
    errors: list[str] = []
    login_path = ROOT / "login-a.html"
    if not login_path.exists():
        errors.append("로그인 화면이 없습니다")
    else:
        parser, source = parse(login_path)
        if not any(tag == "h1" for tag, _ in parser.elements):
            errors.append("로그인 화면에 h1이 없습니다")
        for autocomplete in ("username", "current-password"):
            if f'autocomplete="{autocomplete}"' not in source:
                errors.append(f"로그인 화면에 자동완성 값이 없습니다: {autocomplete}")
        if "11-first-password.html" not in source:
            errors.append("로그인 화면이 최초 로그인 설정으로 연결되지 않습니다")
    first_source = (ROOT / "11-first-password.html").read_text(encoding="utf-8")
    claude_source = (ROOT / "11b-claude-connect.html").read_text(encoding="utf-8")
    project_source = (ROOT / "11c-project-select.html").read_text(encoding="utf-8")
    if "11b-claude-connect.html" not in first_source:
        errors.append("비밀번호 설정 뒤 Claude Code 연결 화면으로 이어지지 않습니다")
    if "novalidate" not in first_source or "auth-field-error" not in first_source:
        errors.append("비밀번호 설정 화면이 브라우저 기본 툴팁 대신 입력 항목 아래 오류를 사용하지 않습니다")
    for phrase in ("Claude Code 계정 연결", "승인 화면 열기", "승인 코드", "나중에 연결"):
        if phrase not in claude_source:
            errors.append(f"Claude Code 연결 필수 내용이 없습니다: {phrase}")
    if claude_source.count("11c-project-select.html") < 2:
        errors.append("Claude 연결과 나중에 연결이 모두 프로젝트 선택으로 이어지지 않습니다")
    for phrase in ("프로젝트 선택", "프로젝트 열기"):
        if phrase not in project_source:
            errors.append(f"프로젝트 선택 화면의 필수 내용이 없습니다: {phrase}")
    if "role') === 'super'" in first_source or "00-project-management.html" in first_source:
        errors.append("슈퍼관리자가 최초 Claude Code 연결 단계를 건너뜁니다")
    return errors


def validate_detail_headers() -> list[str]:
    detail_pages = (
        "00d-project-detail.html",
        "00e-project-failure-detail.html",
        "01b-received-document-edit.html",
        "01d-received-document-processing.html",
        "02a-requirement-detail.html",
        "03a-definition-detail.html",
        "04a-brd-detail.html",
        "05a-frd-workbench.html",
        "06a-dev-request-detail.html",
        "07a-menu-tree-workbench.html",
    )
    errors: list[str] = []
    for filename in detail_pages:
        source = (ROOT / filename).read_text(encoding="utf-8")
        if 'class="breadcrumb"' in source:
            errors.append(f"{filename}: 상세 화면에 한 단계짜리 경로 표시가 남아 있습니다")
    return errors


def validate_common_shell() -> list[str]:
    source = (ROOT / "_shell.js").read_text(encoding="utf-8")
    errors: list[str] = []
    versioned_brand = re.search(
        r'>WE-ADP Builder <small class="app-header__version">v\d+\.\d+\.\d+(?:[-+][^<]+)?</small></span>',
        source,
    )
    if versioned_brand is None or "<span>WE</span>" in source:
        errors.append("_shell.js: Top 제품명이 WE-ADP Builder가 아닙니다")
    if '<a class="app-header__brand"' in source:
        errors.append("_shell.js: Top 로고에 Builder 이동 링크가 남았습니다")
    if "app-header__builder-entry" not in source or ">Builder로 이동</a>" not in source:
        errors.append("_shell.js: 관리 화면의 Builder 이동 버튼이 없습니다")
    management_routes = source.split("const 관리_목업_경로 = {", 1)[-1].split("};", 1)[0]
    if not re.search(r"projects:\s*'00-project-management\.html'", management_routes):
        errors.append("_shell.js: 관리 메뉴의 프로젝트가 프로젝트 관리 목업을 가리키지 않습니다")
    if '<a href="00-project-management.html">프로젝트 관리</a>' not in source:
        errors.append("_shell.js: 사용자 팝업의 프로젝트 관리 링크가 프로젝트 관리 목업을 가리키지 않습니다")
    artifact_menu = source.split("const 산출물_메뉴 = [", 1)[-1].split("];", 1)[0]
    hidden_menu_keys = ("received-docs", "requirements", "definitions", "brd")
    for key in hidden_menu_keys:
        if re.search(rf"\['{key}',", artifact_menu):
            errors.append(f"_shell.js: 숨긴 산출물 메뉴가 남아 있습니다: {key}")
    if "['frds', 'FRD 작업']" not in artifact_menu:
        errors.append("_shell.js: 산출물 메뉴가 FRD 작업부터 시작하지 않습니다")

    thymeleaf_source = (ROOT.parent.parent / "src" / "main" / "resources" / "templates" / "fragments" / "parts.html").read_text(encoding="utf-8")
    artifact_nav = thymeleaf_source.split("<th:block th:if=\"${kind == '산출물'}\">", 1)[-1].split("</th:block>", 1)[0]
    for key in hidden_menu_keys:
        if f"/artifacts/{key}" in artifact_nav:
            errors.append(f"parts.html: 숨긴 산출물 메뉴가 남아 있습니다: {key}")
    if ">FRD 작업</a>" not in artifact_nav:
        errors.append("parts.html: 산출물 메뉴가 FRD 작업부터 시작하지 않습니다")

    menu_keys = re.findall(r"\['([^']+)', '[^']+'\]", source.split("const 관리_메뉴", 1)[0])
    route_block = source.split("const 산출물_목업_경로 = {", 1)[-1].split("};", 1)[0]
    for key in menu_keys:
        if key == "--":
            continue
        key_pattern = rf"(?:'{re.escape(key)}'|{re.escape(key)}):\s*'([^']+)'"
        match = re.search(key_pattern, route_block)
        if not match:
            errors.append(f"_shell.js: {key} 메뉴의 목업 경로가 없습니다")
            continue
        target = match.group(1).split("?", 1)[0]
        if not (ROOT / target).exists():
            errors.append(f"_shell.js: {key} 메뉴가 없는 파일을 가리킵니다: {target}")
    return errors


def validate_mock_css_tokens() -> list[str]:
    static_css = ROOT.parent.parent / "src" / "main" / "resources" / "static" / "css"
    css_paths = [
        static_css / "tokens.css",
        static_css / "reset.css",
        static_css / "typography.css",
        static_css / "components.css",
        static_css / "shell.css",
        static_css / "screens.css",
        ROOT / "_mock.css",
    ]
    sources = [path.read_text(encoding="utf-8") for path in css_paths]
    defined = set(re.findall(r"(--[a-z0-9-]+)\s*:", "\n".join(sources)))
    used = set(re.findall(r"var\((--[a-z0-9-]+)", sources[-1]))
    return [f"_mock.css: 정의되지 않은 CSS 토큰을 사용합니다: {name}" for name in sorted(used - defined)]


def validate_design_guide() -> list[str]:
    guide_path = ROOT / "_style-guide.html"
    css_path = ROOT / "_design-guide.css"
    script_path = ROOT / "_design-guide.js"
    if not guide_path.exists():
        return ["디자인가이드 화면이 없습니다"]

    parser, source = parse(guide_path)
    errors: list[str] = []
    if not css_path.exists():
        errors.append("디자인가이드 전용 CSS가 없습니다")
    if not script_path.exists():
        errors.append("디자인가이드 상호작용 스크립트가 없습니다")
    if not any(tag == "h1" for tag, _ in parser.elements):
        errors.append("디자인가이드가 h1으로 시작하지 않습니다")
    for asset in ("_design-guide.css", "_design-guide.js"):
        if asset not in source:
            errors.append(f"디자인가이드가 {asset}를 사용하지 않습니다")
    for tab in ("컴포넌트", "색상", "공통 셸", "간격과 형태", "타이포그래피", "화면 패턴", "사용 규칙"):
        if tab not in source:
            errors.append(f"디자인가이드 필수 영역이 없습니다: {tab}")
    for control_id in ("guide-edit-toggle", "copy-guide-prompt", "save-guide-draft", "undo-guide-change", "reset-guide-changes"):
        if f'id="{control_id}"' not in source:
            errors.append(f"디자인가이드 변경 관리 조작이 없습니다: {control_id}")
    if source.count('data-token="--') < 12:
        errors.append("디자인가이드에서 편집 가능한 토큰이 12개보다 적습니다")
    if 'data-pattern-width="mobile"' not in source:
        errors.append("디자인가이드에 375px 화면 패턴 미리보기가 없습니다")
    if "style=" in source:
        errors.append("디자인가이드 HTML에 인라인 스타일이 있습니다")
    if css_path.exists() and "@media (max-width: 760px)" not in css_path.read_text(encoding="utf-8"):
        errors.append("디자인가이드에 모바일 반응형 기준이 없습니다")

    static_css = ROOT.parent.parent / "src" / "main" / "resources" / "static" / "css"
    component_source = (static_css / "components.css").read_text(encoding="utf-8")
    shell_source = (static_css / "shell.css").read_text(encoding="utf-8")
    main_content_rule = shell_source.split(".app-main > * {", 1)[-1].split("}", 1)[0]
    if "max-width" in main_content_rule or "margin-inline: auto" in main_content_rule:
        errors.append("고해상도에서 업무 콘텐츠 너비를 제한하거나 가운데 고정하고 있습니다")
    cell_rule = shell_source.split(".cell {", 1)[-1].split("}", 1)[0]
    if "display: inline-flex" not in cell_rule or "white-space: nowrap" not in cell_rule:
        errors.append("표의 주 정보와 보조정보가 한 줄로 표시되지 않습니다")
    if not re.search(r"\.data-table--dense td\s*\{[^}]*white-space:\s*nowrap", shell_source):
        errors.append("조밀한 표의 행이 한 줄로 고정되지 않습니다")
    button_rule = component_source.split(".button {", 1)[-1].split("}", 1)[0]
    if "border-radius: var(--radius-control)" not in button_rule:
        errors.append("공통 버튼이 라운딩 사각형 토큰을 사용하지 않습니다")
    if "border-radius: var(--radius-pill)" in button_rule:
        errors.append("공통 버튼이 알약형 라운딩을 사용합니다")
    primary_button_rule = component_source.split(".button--primary {", 1)[-1].split("}", 1)[0]
    if "var(--color-action-primary)" not in primary_button_rule:
        errors.append("주요 버튼이 전용 행동 색상 토큰을 사용하지 않습니다")
    if "var(--color-ink)" in primary_button_rule:
        errors.append("주요 버튼이 본문 검정색을 사용합니다")

    auth_source = (ROOT / "_auth-concepts.css").read_text(encoding="utf-8")
    if "--control-radius:8px" not in auth_source or "border-radius:var(--control-radius)" not in auth_source:
        errors.append("로그인·최초 설정 버튼이 공통 라운딩 사각형 기준과 다릅니다")
    if "--primary-action:#62517d" not in auth_source or "background:var(--primary-action)" not in auth_source:
        errors.append("로그인·최초 설정 버튼이 Builder 주요 행동 색상과 다릅니다")
    if ".guide-choice-group" not in css_path.read_text(encoding="utf-8"):
        errors.append("입력과 선택 예제가 선택 유형별 세로 리듬을 구분하지 않습니다")
    for status_variant in ("waiting", "progress", "review", "complete", "error"):
        if f"status-badge--{status_variant}" not in component_source:
            errors.append(f"공통 진행 상태 배지 변형이 없습니다: {status_variant}")
    if "분류 태그" not in source or "status-badge" not in source:
        errors.append("디자인가이드가 분류 태그와 진행 상태를 구분하지 않습니다")
    return errors


def main() -> int:
    failures: list[str] = []
    for filename in PRODUCT_PAGES:
        path = ROOT / filename
        if not path.exists():
            failures.append(f"{filename}: 파일이 없습니다")
            continue
        failures.extend(f"{filename}: {message}" for message in validate_page(path))
        source = path.read_text(encoding="utf-8")
        failures.extend(check_frd_naming(path, source))
        failures.extend(check_frd_states(path, source))
        failures.extend(check_frd_no_screen_row(path, source))
        failures.extend(check_frd_wizard_exits(path, source))

    frd_concepts_path = ROOT / "_frd-concepts.js"
    failures.extend(check_frd_naming(
        frd_concepts_path, frd_concepts_path.read_text(encoding="utf-8")))

    failures.extend(f"01-received-docs.html: {message}" for message in validate_received_docs())
    failures.extend(f"01a-received-document-register.html: {message}" for message in validate_registration())
    failures.extend(f"01b-received-document-edit.html: {message}" for message in validate_document_detail())
    failures.extend(f"03a-definition-detail.html: {message}" for message in validate_definition_detail())
    failures.extend(f"요구사항 화면: {message}" for message in validate_requirements_navigation())
    failures.extend(f"BRD 화면: {message}" for message in validate_brd_navigation())
    failures.extend(f"FRD 작업 화면: {message}" for message in validate_frd_screens())
    failures.extend(f"메뉴구조도 IA 화면: {message}" for message in validate_menu_ia())
    failures.extend(f"개발요청서 화면: {message}" for message in validate_dev_requests())
    failures.extend(f"솔루션 목업: {message}" for message in validate_solution_mockups())
    failures.extend(f"목록 페이징: {message}" for message in validate_list_pagination())
    failures.extend(f"00-project-management.html: {message}" for message in validate_project_management())
    failures.extend(f"00a-project-register.html: {message}" for message in validate_project_registration())
    failures.extend(f"사용자 관리 화면: {message}" for message in validate_user_management())
    failures.extend(f"로그인·최초 설정: {message}" for message in validate_auth_flow())
    failures.extend(f"상세 화면 머리: {message}" for message in validate_detail_headers())
    failures.extend(validate_common_shell())
    failures.extend(validate_mock_css_tokens())
    failures.extend(f"디자인가이드: {message}" for message in validate_design_guide())

    if failures:
        print("목업 계약 검사 실패")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print(f"목업 계약 검사 통과: 제품 화면 {len(PRODUCT_PAGES)}개")
    return 0


if __name__ == "__main__":
    sys.exit(main())
