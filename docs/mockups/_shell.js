/*
  목업용 정적 껍데기 — 실물 Thymeleaf 조각의 사본이다.

  정본은 코드다: src/main/resources/templates/fragments/{shell,parts}.html
  설계 정본:     docs/superpowers/specs/2026-08-09-screen-shell-design.md

  ⚠ 이것은 사본이라 언젠가 어긋난다. 어긋나면 **코드가 맞고 이것이 틀린 것**이다.
     껍데기를 고칠 일이 생기면 코드를 고치고 여기 옮겨 적어라. 반대로 하지 마라.

  왜 사본을 두나 — 목업이 실물과 같아 보여야 「이 화면 이상하다」가 화면 탓인지
  껍데기 탓인지 갈린다. CSS 는 사본이 아니라 **실물 파일을 그대로 링크**한다.

  쓰는 법 — 화면 파일은 본문만 쓴다:

    <template id="본문"> ...이 화면 내용만... </template>
    <script src="_shell.js"></script>
    <script>틀({제목:'받은 문서', 모양:'산출물', 지금:'received-docs',
                프로젝트이름:'G2C', 프로젝트번호:1, 프로젝트들:['G2C','서울페이']})</script>

  인자는 실물과 같다 — 제목 · 모양('산출물'|'관리'|'카드'|'꽉') · 지금 · 본문.
  프로젝트이름·프로젝트번호는 조각 인자가 아니라 실물에서 ProjectContextInterceptor 가
  얹는 값인데, 계약 검사가 그 둘까지 본다(없으면 링크가 /projects//artifacts/brd 로 나간다).
*/

/** 산출물 메뉴. 열쇠 순서와 구분선 자리를 parts.html 에서 그대로 옮겼다. */
const 산출물_메뉴 = [
  ['frds', 'FRD 작업'],
  ['srts', 'SRT'],
  ['dev-requests', '개발요청서'],
  ['menu-tree', 'IA'],
  ['design-guide', '디자인가이드'],
  ['business-language', '정책·표준용어'],
  ['--'],
  ['solution-mockups', '솔루션 템플릿'],
  ['--'],
  ['functional-specs', '기능명세서'],
  ['screen-designs', '화면설계서'],
  ['unit-tests', '단위테스트'],
  ['integration-tests', '통합테스트'],
  ['user-manual', '사용자 매뉴얼'],
];

/** 메뉴에서 숨긴 화면도 직접 주소로 열 수 있도록 실물 ShellContract.ARTIFACT_KEYS 를 그대로 옮긴다. */
const 산출물_열쇠 = [
  'received-docs', 'requirements', 'definitions', 'brd',
  ...산출물_메뉴.filter(([열쇠]) => 열쇠 !== '--').map(([열쇠]) => 열쇠),
];

const 관리_메뉴 = [['projects', '프로젝트 관리'], ['accounts', '사용자 관리']];

const 관리_목업_경로 = {
  projects: '00-project-management.html',
  accounts: '00b-user-management.html',
};

/** 정적 목업에서 왼쪽 메뉴를 눌렀을 때 열 화면. 아직 개별 화면이 없는 후속 산출물은 10-other에서 함께 확인한다. */
const 산출물_목업_경로 = {
  'received-docs': '01-received-docs.html',
  requirements: '02-requirements.html',
  definitions: '03-definitions.html',
  brd: '04-brd.html',
  frds: '05-frds.html',
  srts: '_srt-list.html',
  'menu-tree': '07-menu-tree.html',
  'dev-requests': '06-dev-requests.html',
  'design-guide': '12-design-guide.html',
  'business-language': '_business-policy.html',
  'solution-mockups': '08-solution-mockups.html',
  'functional-specs': '10-other.html?artifact=functional-specs',
  'screen-designs': '10-other.html?artifact=screen-designs',
  'unit-tests': '10-other.html?artifact=unit-tests',
  'integration-tests': '10-other.html?artifact=integration-tests',
  'user-manual': '10-other.html?artifact=user-manual',
};

/** 흰색=화이트존, 빨강=레드존, 초록=그린존. */
const 산출물_색 = {
  'received-docs': 'white', requirements: 'white', definitions: 'white',
  brd: 'white', frds: 'white', srts: 'white', 'menu-tree': 'white',
  'dev-requests': 'white', 'design-guide': 'white', 'business-language': 'white', 'solution-mockups': 'red',
  'functional-specs': 'green', 'screen-designs': 'green', 'unit-tests': 'green', 'integration-tests': 'green',
  'user-manual': 'green',
};

/** ShellContract.확인 의 사본 — 모양이 프로젝트 안인데 이름·번호가 없으면 던진다. */
const 프로젝트_안 = ['산출물', '꽉'];

/**
 * ShellContract.확인 을 검사 넷까지 그대로 옮겼다 (2026-08-10).
 *
 * ⚠ 종전 사본은 검사가 둘 반뿐이었다 — 프로젝트번호를 아예 안 보고, 카드·꽉에 지금이
 *   들어와도 통과시키고, 지금 이 「열쇠 열 개 중 하나인가」를 안 봤다. 그래서
 *   `지금:'requirement'` 같은 오타가 목업에서 조용히 통과했다(실물은 던진다).
 */
function 계약확인(설정) {
  if (!['산출물', '관리', '카드', '꽉'].includes(설정.모양)) {
    throw new Error(`껍데기 조각의 모양이 넷 중 하나가 아니다: '${설정.모양}'`);
  }

  if (프로젝트_안.includes(설정.모양)) {
    if (!설정.프로젝트이름) {
      throw new Error(`모양 '${설정.모양}' 는 프로젝트를 고른 뒤에만 뜨는데 프로젝트이름이 비어 있다`);
    }
    if (설정.프로젝트번호 === undefined || 설정.프로젝트번호 === null) {
      throw new Error(`모양 '${설정.모양}' 는 프로젝트를 고른 뒤에만 뜨는데 프로젝트번호가 없다`);
    }
  }

  const 허용 = 설정.모양 === '산출물' ? 산출물_열쇠
    : 설정.모양 === '관리' ? 관리_메뉴.map(([열쇠]) => 열쇠)
      : null;

  if (허용 === null) {
    if (설정.지금 !== undefined && 설정.지금 !== null) {
      throw new Error(`모양 '${설정.모양}' 에는 메뉴가 없는데 지금 이 들어왔다: '${설정.지금}'`);
    }
    return;
  }

  if (!허용.includes(설정.지금)) {
    throw new Error(`모양 '${설정.모양}' 의 지금 이 [${허용}] 중 하나가 아니다: '${설정.지금}'`);
  }
}

function 작업그룹HTML(설정, 지금) {
  if (설정.모양 !== '산출물' || !Array.isArray(설정.프로젝트들) || 설정.프로젝트들.length === 0) return '';
  const 옵션 = 설정.프로젝트들.map((프로젝트, 번호) => `
        <div class="app-nav__project-option" id="mockup-project-option-${번호}" role="option" tabindex="-1"
             aria-selected="${프로젝트 === 설정.프로젝트이름}" data-project-switch-option>
          <span class="app-nav__project-option-label">${프로젝트}</span>
          <svg class="app-nav__project-option-check" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6"/></svg>
        </div>`).join('');
  return `<div class="app-nav__project">
    <div class="app-nav__project-combobox" data-project-switch>
      <button class="app-nav__project-trigger" id="project-switcher" type="button" role="combobox"
              aria-expanded="false" aria-controls="project-switcher-list" aria-haspopup="listbox"
              data-project-switch-trigger>
        <span class="app-nav__project-copy">
          <span class="app-nav__project-label">작업그룹</span>
          <span class="app-nav__project-value">${설정.프로젝트이름}</span>
        </span>
        <svg class="app-nav__project-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m7 10 5 5 5-5"/></svg>
      </button>
      <div class="app-nav__project-list" id="project-switcher-list" role="listbox" aria-label="프로젝트 목록" tabindex="-1" hidden>${옵션}
      </div>
    </div>
  </div>`;
}

function 메뉴HTML(모양, 지금, 설정) {
  const 항목 = 모양 === '산출물' ? 산출물_메뉴 : 관리_메뉴;
  const 줄 = 항목.map(([열쇠, 이름]) => {
    if (열쇠 === '--') return '<hr>';
    const 경로 = 모양 === '산출물'
      ? 산출물_목업_경로[열쇠]
      : 관리_목업_경로[열쇠];
    const 클래스 = 모양 === '산출물'
      ? `class="artifact-link artifact-link--${산출물_색[열쇠]}"`
      : '';
    const 현재 = 지금 === 열쇠 ? 'aria-current="page"' : '';
    return `<a href="${경로}" ${클래스} ${현재}>${이름}</a>`;
  }).join('\n    ');
  return `<nav class="app-nav" id="builder-navigation">
    ${작업그룹HTML(설정, 지금)}
    ${모양 === '산출물' ? '' : `<p class="app-nav__title">${모양}</p>`}
    ${줄}
  </nav>`;
}

function 머리HTML(설정) {
  // 로그인 계열('카드')에는 알림·사람 자리가 없다 — 실물도 sec:authorize 로 가린다.
  const 로그인함 = 설정.모양 !== '카드';
  const 안읽은 = 설정.안읽은알림 > 0 ? `<span class="unread">${설정.안읽은알림}</span>` : '';
  const 기본알림 = 설정.안읽은알림 > 0 ? [
    {text: '8/13 운영회의 회의록 정리가 완료되었습니다.', when: '방금 전', read: false},
    {text: 'FRD-003 작업이 수정되었습니다.', when: '12분 전', read: false},
    {text: '요구사항정의서 RD-031이 변경되었습니다.', when: '어제', read: true},
  ] : [];
  const 알림목록 = Array.isArray(설정.알림) ? 설정.알림 : 기본알림;
  const 알림줄 = 알림목록.length
    ? 알림목록.map(n =>
        `<a href="#" class="notice notice--${n.read ? 'read' : 'unread'}">
          <span class="notice__state" aria-hidden="true"></span>
          <span class="notice__copy"><strong>${n.text}</strong><time>${n.when}</time></span>
        </a>`).join('\n        ')
    : `<div class="notification-empty"><span class="notification-empty__icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg></span><strong>새 알림이 없습니다.</strong><p>문서 처리나 작업 변경이 생기면 알려드립니다.</p></div>`;
  // 정적 목업은 화면 사이를 검토하기 쉽도록 모두 슈퍼계정 관점으로 연결한다.
  // 실물은 parts.html 의 sec:authorize 로 슈퍼계정에게만 같은 링크를 보여 준다.
  const 관리링크 = `<a href="00-project-management.html">프로젝트 관리</a>
      <a href="00b-user-management.html">사용자 관리</a>
      <hr>`;

  const 메뉴버튼 = 설정.지금 == null ? '' : `<button class="app-nav-toggle" type="button" data-nav-toggle aria-controls="builder-navigation" aria-expanded="true" aria-label="메뉴 접기" title="메뉴 접기">
    <svg class="app-nav-toggle__collapse" viewBox="0 0 24 24" aria-hidden="true"><path d="m14 6-6 6 6 6"/></svg>
    <svg class="app-nav-toggle__expand" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16"/></svg>
  </button>`;
  const 빌더이동 = 설정.모양 === '관리'
    ? `<a class="app-header__builder-entry" href="05-frds.html">Builder로 이동</a>`
    : '';
  const 모바일빌더이동 = 설정.모양 === '관리'
    ? `<a class="app-header__builder-menu-entry" href="05-frds.html">Builder로 이동</a><hr class="app-header__builder-menu-entry">`
    : '';

  return `<header class="app-header">
  <span class="app-header__brand">WE-ADP Builder <small class="app-header__version">v1.0.1</small></span>
  ${메뉴버튼}
  ${빌더이동}
  <span class="app-header__gap"></span>
  ${로그인함 ? `
  <details class="pop pop--notifications" name="머리팝업">
    <summary class="notification-trigger" aria-label="알림${설정.안읽은알림 > 0 ? `, 미확인 ${설정.안읽은알림}개` : ''}">
      <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg>${안읽은}
    </summary>
    <div class="pop__body notification-panel">
      <header class="notification-panel__head"><div><h2>알림</h2>${설정.안읽은알림 > 0 ? `<p>미확인 알림 ${설정.안읽은알림}개</p>` : '<p>새 알림 없음</p>'}</div></header>
      <div class="notification-panel__list">${알림줄}</div>
    </div>
  </details>
  <details class="pop" name="머리팝업">
    <summary>${설정.사람 || '이영희'}</summary>
    <div class="pop__body">
      ${모바일빌더이동}
      ${관리링크}
      <a href="#">비밀번호 바꾸기</a>
      <a href="#">Claude 다시 연결</a>
      <hr>
      <button type="submit">로그아웃</button>
    </div>
  </details>` : ''}
</header>`;
}

function 틀(설정) {
  const 요청한산출물 = new URLSearchParams(window.location.search).get('artifact');
  if (설정.모양 === '산출물' && 요청한산출물 && 산출물_목업_경로[요청한산출물]) {
    설정 = {...설정, 지금: 요청한산출물};
  }
  계약확인(설정);

  const 본문 = document.getElementById('본문');
  if (!본문) throw new Error('<template id="본문"> 이 없다');

  document.title = `${설정.제목} · 빌더 · 목업`;

  const 메뉴있다 = 설정.모양 === '산출물' || 설정.모양 === '관리';
  const 쉘클래스 = 메뉴있다 ? 'app-shell'
    : 설정.모양 === '카드' ? 'app-shell app-shell--card' : 'app-shell app-shell--wide';
  const 본문클래스 = 설정.모양 === '카드' ? 'app-card' : 'app-main';

  // ⚠ 본문을 <div> 로 한 겹 감싼다. 실물은 화면이 넘기는 조각이 자기 뿌리 태그
  //   (<div th:fragment="본문">)를 데려오므로 DOM 이 main > div > h1 이고,
  //   shell.css 의 `.app-main > * > h1` · `> h2` · `> .table-wrap` 셋이 그 모양을 노린다.
  //   감싸지 않으면 목업만 그 셋이 안 먹어 제목·절 여백이 통째로 사라진다 (2026-08-10 실측).
  document.body.innerHTML = `
<a class="skip-link" href="#main">본문으로 이동</a>
${머리HTML(설정)}
<div class="${쉘클래스}">
  ${메뉴있다 ? 메뉴HTML(설정.모양, 설정.지금, 설정) : ''}
  <main id="main" class="${본문클래스}">
    <div class="page-loading-overlay" data-page-loading-overlay role="status" aria-label="화면 이동 처리 중" hidden>
      <span class="page-loading-indicator"><span class="page-loading-spinner" aria-hidden="true"></span></span>
    </div>
    <div>${본문.innerHTML}</div>
  </main>
</div>`;

  if (new URLSearchParams(window.location.search).get('notifications') === 'open') {
    document.querySelector('.pop--notifications')?.setAttribute('open', '');
  }

  if (window.matchMedia('(max-width: 767px)').matches) {
    document.querySelector('.app-nav [aria-current="page"]')?.scrollIntoView({block: 'nearest', inline: 'center'});
  }
}

(() => {
  const openPopupSelector = ".app-header details.pop[open]";
  const submitLoadingSelector = "button[data-submit-loading]";
  const listLoadingRegionSelector = "[data-list-loading-region]";
  const listLoadingTriggerSelector = "form[data-list-loading-trigger]";
  const listLoadingLinksSelector = "[data-list-loading-links] a";
  const pageLoadingOverlaySelector = "[data-page-loading-overlay]";
  const navToggleSelector = "[data-nav-toggle]";
  const projectSwitchSelector = "[data-project-switch]";
  const projectSwitchTriggerSelector = "[data-project-switch-trigger]";
  const projectSwitchOptionSelector = "[data-project-switch-option]";
  const navCollapsedClass = "is-nav-collapsed";
  const navCollapsedStorageKey = "builder-nav-collapsed";
  const pageLoadingDelay = 150;
  let pageLoadingTimer = null;

  function storedNavCollapsed() {
    try {
      return window.localStorage.getItem(navCollapsedStorageKey) === "true";
    } catch (_error) {
      return false;
    }
  }

  function storeNavCollapsed(collapsed) {
    try {
      window.localStorage.setItem(navCollapsedStorageKey, String(collapsed));
    } catch (_error) {
      // 저장소를 사용할 수 없어도 현재 화면의 메뉴 접기는 유지한다.
    }
  }

  function applyNavCollapsed(collapsed) {
    document.body.classList.toggle(navCollapsedClass, collapsed);
    document.querySelectorAll(navToggleSelector).forEach((button) => {
      button.setAttribute("aria-expanded", String(!collapsed));
      button.setAttribute("aria-label", collapsed ? "메뉴 펼치기" : "메뉴 접기");
      button.title = collapsed ? "메뉴 펼치기" : "메뉴 접기";
    });
  }

  function initializeNavToggle() {
    const button = document.querySelector(navToggleSelector);
    if (!button) return;

    const narrowScreen = window.matchMedia("(max-width: 767px)");
    applyNavCollapsed(narrowScreen.matches ? false : storedNavCollapsed());
    button.addEventListener("click", () => {
      const collapsed = !document.body.classList.contains(navCollapsedClass);
      applyNavCollapsed(collapsed);
      storeNavCollapsed(collapsed);
    });
    narrowScreen.addEventListener("change", (event) => {
      applyNavCollapsed(event.matches ? false : storedNavCollapsed());
    });
  }

  document.addEventListener("DOMContentLoaded", initializeNavToggle);

  function 작업그룹목록열기(선택기, 열기) {
    const 버튼 = 선택기?.querySelector(projectSwitchTriggerSelector);
    const 목록 = 선택기?.querySelector("[role='listbox']");
    if (!버튼 || !목록) return;
    버튼.setAttribute("aria-expanded", String(열기));
    목록.hidden = !열기;
  }

  document.addEventListener("click", (event) => {
    const 버튼 = event.target.closest?.(projectSwitchTriggerSelector);
    const 옵션 = event.target.closest?.(projectSwitchOptionSelector);
    const 선택기 = event.target.closest?.(projectSwitchSelector);
    if (옵션 && 선택기) {
      선택기.querySelectorAll(projectSwitchOptionSelector).forEach((항목) =>
        항목.setAttribute("aria-selected", String(항목 === 옵션)));
      선택기.querySelector(".app-nav__project-value").textContent =
        옵션.querySelector(".app-nav__project-option-label").textContent;
      작업그룹목록열기(선택기, false);
      return;
    }
    if (버튼 && 선택기) {
      작업그룹목록열기(선택기, 버튼.getAttribute("aria-expanded") !== "true");
      return;
    }
    document.querySelectorAll(projectSwitchSelector).forEach((항목) => 작업그룹목록열기(항목, false));
  });

  function closeHeaderPopups(except) {
    document.querySelectorAll(openPopupSelector).forEach((popup) => {
      if (popup !== except) popup.removeAttribute("open");
    });
  }

  document.addEventListener("click", (event) => {
    const currentPopup = event.target.closest?.(".app-header details.pop") ?? null;
    closeHeaderPopups(currentPopup);
  });

  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;

    const openPopup = document.querySelector(openPopupSelector);
    if (!openPopup) return;

    openPopup.removeAttribute("open");
    openPopup.querySelector(":scope > summary")?.focus();
  });

  function submitButton(form, submitter) {
    if (submitter?.matches?.(submitLoadingSelector)) return submitter;
    return Array.from(document.querySelectorAll(submitLoadingSelector))
      .find((button) => button.form === form) ?? null;
  }

  function startSubmitLoading(form, button) {
    button.dataset.originalAriaLabel = button.getAttribute("aria-label") ?? "";
    button.classList.add("is-submit-loading");
    button.setAttribute("aria-label", button.dataset.submitLoading);
    button.disabled = true;
    if (form) {
      form.dataset.submitting = "true";
      form.setAttribute("aria-busy", "true");
    }
  }

  function resetSubmitLoading() {
    document.querySelectorAll(`${submitLoadingSelector}.is-submit-loading`).forEach((button) => {
      const originalAriaLabel = button.dataset.originalAriaLabel;
      button.classList.remove("is-submit-loading");
      button.disabled = false;
      if (originalAriaLabel) button.setAttribute("aria-label", originalAriaLabel);
      else button.removeAttribute("aria-label");
      delete button.dataset.originalAriaLabel;
    });
    document.querySelectorAll('form[data-submitting="true"]').forEach((form) => {
      delete form.dataset.submitting;
      form.removeAttribute("aria-busy");
    });
  }

  function startListLoading() {
    const region = document.querySelector(listLoadingRegionSelector);
    if (!region || region.dataset.listLoading === "true") return false;

    region.dataset.listLoading = "true";
    region.setAttribute("aria-busy", "true");
    region.querySelector("[data-list-loading-overlay]")?.removeAttribute("hidden");
    document.querySelectorAll(`${listLoadingTriggerSelector}, [data-list-loading-links]`).forEach((trigger) => {
      trigger.classList.add("is-list-loading-trigger");
      trigger.setAttribute("aria-disabled", "true");
    });
    return true;
  }

  function resetListLoading() {
    document.querySelectorAll(`${listLoadingRegionSelector}[data-list-loading="true"]`).forEach((region) => {
      delete region.dataset.listLoading;
      region.removeAttribute("aria-busy");
      region.querySelector("[data-list-loading-overlay]")?.setAttribute("hidden", "");
    });
    document.querySelectorAll(".is-list-loading-trigger").forEach((trigger) => {
      trigger.classList.remove("is-list-loading-trigger");
      trigger.removeAttribute("aria-disabled");
    });
  }

  function startPageLoading() {
    const overlay = document.querySelector(pageLoadingOverlaySelector);
    if (!overlay || overlay.dataset.loadingPending === "true") return;

    overlay.dataset.loadingPending = "true";
    pageLoadingTimer = window.setTimeout(() => {
      overlay.removeAttribute("hidden");
      overlay.setAttribute("aria-busy", "true");
    }, pageLoadingDelay);
  }

  function resetPageLoading() {
    if (pageLoadingTimer !== null) window.clearTimeout(pageLoadingTimer);
    pageLoadingTimer = null;
    document.querySelectorAll(pageLoadingOverlaySelector).forEach((overlay) => {
      overlay.setAttribute("hidden", "");
      overlay.removeAttribute("aria-busy");
      delete overlay.dataset.loadingPending;
    });
  }

  function submitsCurrentPage(form) {
    const target = form.getAttribute("target");
    return form.method.toLowerCase() !== "dialog" && (!target || target === "_self");
  }

  function internalNavigationLink(event) {
    const link = event.target.closest?.("a[href]");
    if (!link || event.defaultPrevented || event.button !== 0
        || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey
        || link.hasAttribute("download") || (link.target && link.target !== "_self")
        || link.closest("[data-list-loading-links]")) return null;

    const href = link.getAttribute("href");
    if (!href || href.startsWith("#")) return null;

    const destination = new URL(link.href, window.location.href);
    if (destination.origin !== window.location.origin) return null;
    if (destination.pathname === window.location.pathname
        && destination.search === window.location.search
        && destination.hash) return null;
    return link;
  }

  document.addEventListener("submit", (event) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement)) return;
    if (form.dataset.submitting === "true") {
      event.preventDefault();
      return;
    }

    if (form.matches(listLoadingTriggerSelector) && !startListLoading()) {
      event.preventDefault();
      return;
    }
    if (!form.matches(listLoadingTriggerSelector) && submitsCurrentPage(form)) {
      queueMicrotask(() => {
        if (!event.defaultPrevented) startPageLoading();
      });
    }

    const button = submitButton(form, event.submitter);
    if (button) startSubmitLoading(form, button);
  });

  document.addEventListener("click", (event) => {
    const link = event.target.closest?.(listLoadingLinksSelector);
    if (!link || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if (!startListLoading()) event.preventDefault();
  });

  document.addEventListener("click", (event) => {
    if (internalNavigationLink(event)) startPageLoading();
  });

  document.addEventListener("change", (event) => {
    const select = event.target.closest?.("[data-list-loading-page-size]");
    select?.form?.requestSubmit();
  });

  // 정적 목업에서 실제 제출 폼 대신 type="button"으로 표현한 거래도 로딩 상태를 확인한다.
  document.addEventListener("click", (event) => {
    const button = event.target.closest?.(`${submitLoadingSelector}[type="button"]`);
    if (!button || button.classList.contains("is-submit-loading")) return;
    startSubmitLoading(button.form, button);
  });

  window.addEventListener("pageshow", () => {
    resetSubmitLoading();
    resetListLoading();
    resetPageLoading();
  });
})();
