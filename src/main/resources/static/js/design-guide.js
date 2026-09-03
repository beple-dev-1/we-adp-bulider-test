(() => {
  const guide = document.querySelector("[data-design-guide]");
  if (!guide) return;

  const styleLink = document.querySelector("#dg-system-style");
  const systemButtons = [...guide.querySelectorAll(".dg-native-systems button")];
  const panels = [...guide.querySelectorAll("[data-system-panel]")];
  const facetSelectors = [...guide.querySelectorAll("[data-system-facet]")];

  guide.querySelectorAll("[data-dg-artifact-src]").forEach(element => {
    const source = element.dataset.dgArtifactSrc;
    if (source && guide.dataset.artifactBase) element.setAttribute("src", guide.dataset.artifactBase + source);
  });
  const slotLabels = {
    authrtNm: "권한",
    userNm: "사용자명",
    lspnApinNm: "서비스명",
    menuNm: "메뉴명"
  };
  guide.querySelectorAll("[data-dg-slot]").forEach(element => {
    if (element.matches("img, source")) return;
    if (!/[«»{}]/.test(element.textContent)) return;
    const slot = element.dataset.dgSlot;
    element.textContent = slotLabels[slot] || "정보";
  });
  guide.querySelectorAll('.dg-live-card--component .dg-render-scope input[type="checkbox"], .dg-live-card--component .dg-render-scope input[type="radio"]').forEach(input => {
    if (input.nextElementSibling) return;
    const indicator = document.createElement("i");
    indicator.className = "dg-generated-control-indicator";
    indicator.setAttribute("aria-hidden", "true");
    input.insertAdjacentElement("afterend", indicator);
  });
  guide.querySelectorAll('[data-component-panel="checkbox"]').forEach(card => {
    if (card.closest('[data-system-panel="webview"]')) return;
    const cells = [...card.querySelectorAll('.dg-sp-cell[data-dg-state]')];
    const stateIndexes = new Map();
    cells.forEach(cell => {
      const input = cell.querySelector(':scope > .dg-sp-box > input[type="checkbox"]');
      const label = cell.querySelector('.dg-sp-tag');
      if (!input || !label) return;
      const box = input.parentElement;
      const indicator = input.nextElementSibling;
      if (box) {
        box.style.setProperty("position", "relative", "important");
        box.style.setProperty("width", "18px", "important");
        box.style.setProperty("height", "18px", "important");
      }
      [input, indicator].filter(Boolean).forEach(element => {
        element.style.setProperty("position", "absolute", "important");
        element.style.setProperty("top", "0", "important");
        element.style.setProperty("right", "auto", "important");
        element.style.setProperty("bottom", "auto", "important");
        element.style.setProperty("left", "0", "important");
        element.style.setProperty("margin", "0", "important");
        element.style.setProperty("transform", "none", "important");
      });
      const state = cell.dataset.dgState || "default";
      const stateIndex = stateIndexes.get(state) || 0;
      const selected = stateIndex % 2 === 1;
      input.checked = selected;
      input.setAttribute("aria-label", selected ? "선택함 예시" : "선택 안 함 예시");
      label.textContent = selected ? "선택함" : "선택 안 함";
      if (input.disabled) label.textContent += " · 비활성";
      stateIndexes.set(state, stateIndex + 1);
    });
    const roles = [...card.querySelectorAll('.dg-variant-strip span')];
    if (roles[0]) roles[0].textContent = "선택 안 함";
    if (roles[1]) roles[1].textContent = "선택함";
  });
  guide.querySelectorAll('[data-component-panel="tabs"] .dg-sp-cell[data-dg-variant]').forEach(cell => {
    const tabs = cell.querySelector('.tabs_header');
    if (!tabs) return;
    tabs.classList.remove("type2", "type3");
    if (cell.dataset.dgVariant.endsWith("--type2")) tabs.classList.add("type2");
    if (cell.dataset.dgVariant.endsWith("--type3")) tabs.classList.add("type3");
  });
  guide.querySelectorAll('[data-component-panel="pagination"] .pagination-container').forEach(pagination => {
    const pageNumbers = pagination.querySelector('.page-numbers, .pag_num');
    if (!pageNumbers) return;
    pageNumbers.replaceChildren();
    for (let page = 1; page <= 10; page += 1) {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = String(page);
      button.setAttribute("aria-label", `${page}페이지`);
      if (page === 1) {
        button.className = "on";
        button.setAttribute("aria-current", "page");
      }
      pageNumbers.append(button);
    }
    pagination.dataset.currentPage = "1";
    pagination.dataset.totalPage = "10";
    pagination.querySelectorAll('.firstPageBtn, .previousGroupBtn').forEach(button => {
      button.classList.add("disable");
      button.disabled = true;
    });
  });
  const webviewButtonRoles = new Map([
    ["btn-base", {label: "기본 버튼", group: "action"}],
    ["btn-base--data-color-333", {label: "어두운 버튼", group: "action"}],
    ["btn-base--data-color-454545", {label: "중립 버튼", group: "action"}],
    ["btn-effect", {label: "강조 버튼", group: "action"}],
    ["web-main-btn-area", {label: "다음 단계", group: "action"}],
    ["btn-history-filter-open", {label: "기간 필터", group: "selection"}],
    ["btn-account-setting", {label: "계좌 선택", group: "selection"}],
    ["btn-search-filter-item", {label: "검색 필터", group: "selection"}],
    ["ui-acco--btn", {label: "펼치기", group: "icon"}],
    ["header-link-alarm", {label: "알림", group: "icon"}],
    ["btn-swiper-control", {label: "재생 제어", group: "icon"}],
    ["swiper-button-next", {label: "다음 배너", group: "icon"}],
    ["btn-in-del--data-show-true", {label: "입력값 삭제", group: "icon"}]
  ]);
  const hiddenWebviewButtonVariants = new Set([
    "btn-in-del", "btn-in-del--data-order-3", "btn-store-detail-view",
    "btn-base", "btn-base--data-color-333", "btn-base--data-color-454545",
    "btn-effect", "web-main-btn-area", "btn-swiper-control", "swiper-button-next"
  ]);
  const webviewButtonCells = [...guide.querySelectorAll('[data-system-panel="webview"] [data-component-panel="button"] .dg-sp-cell[data-dg-variant]')];
  const seenWebviewButtonStates = new Set();
  webviewButtonCells.forEach(cell => {
    const variant = cell.dataset.dgVariant;
    const stateKey = `${variant}:${cell.dataset.dgState || "default"}`;
    if (hiddenWebviewButtonVariants.has(variant) || seenWebviewButtonStates.has(stateKey)) {
      cell.hidden = true;
      return;
    }
    seenWebviewButtonStates.add(stateKey);
    if (cell.dataset.dgState === "default") {
      const baseButton = cell.querySelector("button.btn-base");
      if (baseButton) {
        baseButton.removeAttribute("disabled");
        if (variant === "btn-base--data-color-333") baseButton.dataset.color = "333";
        if (variant === "btn-base--data-color-454545") baseButton.dataset.color = "454545";
      }
    }
    const label = cell.querySelector(".dg-sp-tag");
    if (!label) return;
    const role = webviewButtonRoles.get(variant);
    label.textContent = role?.label || variant;
    cell.dataset.dgButtonGroup = role?.group || "other";
    if (cell.dataset.dgState && cell.dataset.dgState !== "default") {
      label.textContent += " · 비활성";
    }
    label.title = variant;
  });

  const webviewButtonGrid = guide.querySelector('[data-system-panel="webview"] [data-component-panel="button"] .dg-sp-grid');
  if (webviewButtonGrid) {
    const buttonCard = webviewButtonGrid.closest(".dg-live-card--component");
    const stateToggle = buttonCard?.querySelector(".dg-state-toggle");
    const cardMeta = buttonCard?.querySelector(".dg-live-card__meta");
    if (stateToggle && cardMeta) {
      cardMeta.append(stateToggle);
      buttonCard.querySelector(".dg-variant-bar")?.remove();
    }
    const groups = [
      {id: "action", label: "행동 버튼", description: "화면의 주요 행동과 다음 단계를 실행합니다."},
      {id: "selection", label: "선택 · 필터", description: "조회 조건이나 적용 대상을 고릅니다."},
      {id: "icon", label: "아이콘 컨트롤", description: "좁은 자리에서 한 가지 조작을 수행합니다."}
    ];
    groups.forEach(group => {
      const cells = webviewButtonCells.filter(cell => !cell.hidden && cell.dataset.dgButtonGroup === group.id);
      if (!cells.length) return;
      const section = document.createElement("section");
      section.className = "dg-button-group";
      section.dataset.buttonGroup = group.id;
      const heading = document.createElement("div");
      heading.className = "dg-button-group__heading";
      const title = document.createElement("h4");
      title.textContent = group.label;
      const description = document.createElement("p");
      description.textContent = group.description;
      heading.append(title, description);
      const items = document.createElement("div");
      items.className = "dg-button-group__items";
      cells.forEach(cell => items.append(cell));
      section.append(heading, items);
      webviewButtonGrid.append(section);
    });

    const familyDefinitions = [
      {color: "primary", label: "주요 행동", description: "화면에서 가장 중요한 다음 행동"},
      {color: "secondary", label: "보조 행동", description: "주요 행동을 보완하는 선택"},
      {color: "tertiary", label: "중립 행동", description: "닫기·취소처럼 우선순위가 낮은 행동"},
      {color: "ghost-dark", label: "어두운 외곽선", description: "밝은 화면에서 경계를 드러내는 행동"},
      {color: "ghost-light", label: "연한 외곽선", description: "경계를 가볍게 표현하는 보조 행동"},
      {color: "green", label: "기관 강조", description: "기관 색상을 사용하는 주요 행동"}
    ];
    const familySection = document.createElement("section");
    familySection.className = "dg-button-group dg-button-group--families";
    familySection.dataset.buttonGroup = "families";
    const familyHeading = document.createElement("div");
    familyHeading.className = "dg-button-group__heading";
    const familyTitle = document.createElement("h4");
    familyTitle.textContent = "버튼 위계와 색상";
    const familyDescription = document.createElement("p");
    familyDescription.textContent = "실제 화면에서 반복되는 대표 스타일을 같은 크기로 비교합니다.";
    familyHeading.append(familyTitle, familyDescription);
    const familyItems = document.createElement("div");
    familyItems.className = "dg-button-family-grid";

    const sourceCell = guide.querySelector('[data-system-panel="webview"] [data-component-panel="button-primary"] .dg-sp-cell[data-dg-variant="data-size-48"][data-dg-state="default"]');
    familyDefinitions.forEach(definition => {
      if (!sourceCell) return;
      const family = document.createElement("div");
      family.className = "dg-button-family";
      family.dataset.color = definition.color;
      const heading = document.createElement("div");
      heading.className = "dg-button-family__heading";
      const title = document.createElement("strong");
      title.textContent = definition.label;
      const description = document.createElement("span");
      description.textContent = definition.description;
      heading.append(title, description);
      const cell = sourceCell.cloneNode(true);
      cell.dataset.dgVariant = `btn-base--data-color-${definition.color}`;
      cell.dataset.dgState = "default";
      cell.hidden = false;
      cell.style.removeProperty("display");
      const button = cell.querySelector("button.btn-base");
      button?.getAttributeNames().filter(name => name.startsWith("data-")).forEach(name => button.removeAttribute(name));
      button?.removeAttribute("disabled");
      if (button) {
        button.dataset.color = definition.color;
        button.dataset.size = "48";
        button.dataset.width = "full";
        const text = button.querySelector(".btn-text");
        if (text) text.textContent = "예시 행동";
      }
      const label = cell.querySelector(".dg-sp-tag");
      if (label) {
        label.textContent = "기본";
        label.title = `${cell.dataset.dgVariant} · default`;
      }
      family.append(heading, cell);
      if (definition.color === "primary") {
        const disabledCell = cell.cloneNode(true);
        disabledCell.dataset.dgState = "disabled";
        disabledCell.querySelector("button.btn-base")?.setAttribute("disabled", "");
        const disabledLabel = disabledCell.querySelector(".dg-sp-tag");
        if (disabledLabel) {
          disabledLabel.textContent = "공통 비활성";
          disabledLabel.title = `${disabledCell.dataset.dgVariant} · disabled`;
        }
        family.append(disabledCell);
      }
      familyItems.append(family);
    });
    if (familyItems.children.length) {
      familySection.append(familyHeading, familyItems);
      webviewButtonGrid.prepend(familySection);
    }
  }

  const promotedWebviewComponents = [
    {id: "button-search-filter-item", category: "선택", label: "필터 칩", description: "조회 조건을 빠르게 고르고 선택 상태를 확인합니다."},
    {id: "button-account-item", category: "업무 UI", label: "계좌 · 카드 선택", description: "계좌나 카드의 이름과 부가 정보를 한 행에서 비교하고 선택합니다."},
    {id: "button-faq-top-10-item", category: "탐색", label: "FAQ 아코디언 항목", description: "질문을 눌러 연결된 답변을 펼치고 접습니다."}
  ];
  const promotedWebviewIds = new Set(promotedWebviewComponents.map(component => component.id));
  guide.querySelectorAll('[data-system-panel="webview"] [data-component-select^="button-"]').forEach(button => {
    const promoted = promotedWebviewIds.has(button.dataset.componentSelect);
    button.hidden = !promoted;
    if (promoted) button.style.removeProperty("display");
    else button.style.setProperty("display", "none", "important");
  });
  guide.querySelectorAll('[data-system-panel="webview"] [data-component-panel^="button-"]').forEach(card => {
    const promoted = promotedWebviewIds.has(card.dataset.componentPanel);
    card.hidden = !promoted;
    if (promoted) card.style.removeProperty("display");
    else card.style.setProperty("display", "none", "important");
  });
  const webviewComponentIndex = guide.querySelector('[data-system-panel="webview"] .dg-component-index');
  const webviewComponentGrid = guide.querySelector('[data-system-panel="webview"] .dg-component-grid--semantic');
  promotedWebviewComponents.forEach(component => {
    const button = guide.querySelector(`[data-system-panel="webview"] [data-component-select="${component.id}"]`);
    const card = guide.querySelector(`[data-system-panel="webview"] [data-component-panel="${component.id}"]`);
    if (!button || !card) return;
    const category = button.querySelector("span");
    const label = button.querySelector("strong");
    const count = button.querySelector("small");
    if (category) category.textContent = component.category;
    if (label) label.textContent = component.label;
    if (count) count.textContent = "대표 표본";
    card.classList.add("dg-webview-promoted");
    const kind = card.querySelector(".dg-live-card__kind");
    const title = card.querySelector("header h3");
    const description = card.querySelector(".dg-live-card__description");
    if (kind) kind.textContent = component.category;
    if (title) title.textContent = component.label;
    if (description) description.textContent = component.description;
    const variantBar = card.querySelector(".dg-variant-bar");
    const stateToggle = variantBar?.querySelector(".dg-state-toggle");
    const meta = card.querySelector(".dg-live-card__meta");
    if (stateToggle && meta) meta.append(stateToggle);
    variantBar?.remove();
    webviewComponentIndex?.append(button);
    webviewComponentGrid?.append(card);
  });

  const webviewInputRoles = new Map([
    ["form-input-box", "기본 입력"],
    ["form-input-box--data-line-color-black", "강조 테두리"]
  ]);
  const hiddenWebviewInputVariants = new Set([
    "form-element-input",
    "form-element-input--data-align-center",
    "form-element-input--data-align-right",
    "form-input-box--data-bg-white"
  ]);
  const webviewInputCard = guide.querySelector('[data-system-panel="webview"] [data-component-panel="text-field"]');
  if (webviewInputCard) {
    webviewInputCard.querySelectorAll('.dg-sp-cell[data-dg-variant]').forEach(cell => {
      const variant = cell.dataset.dgVariant;
      if (hiddenWebviewInputVariants.has(variant) || !webviewInputRoles.has(variant)) {
        cell.hidden = true;
        return;
      }
      const label = cell.querySelector(".dg-sp-tag");
      if (label) {
        label.textContent = webviewInputRoles.get(variant);
        label.title = variant;
      }
    });
    webviewInputCard.querySelector(".dg-variant-bar")?.remove();
  }

  const curateWebviewSpecimen = (component, roles, options = {}) => {
    const card = guide.querySelector(`[data-system-panel="webview"] [data-component-panel="${component}"]`);
    if (!card) return null;
    card.classList.add("dg-curated-specimen");
    card.querySelectorAll('.dg-sp-cell[data-dg-variant]').forEach(cell => {
      const role = roles.get(cell.dataset.dgVariant);
      if (!role) {
        cell.hidden = true;
        return;
      }
      const label = cell.querySelector(".dg-sp-tag");
      if (label) {
        label.textContent = typeof role === "string" ? role : role.label;
        label.title = cell.dataset.dgVariant;
      }
      if (typeof role === "object" && role.group) cell.dataset.dgSpecimenGroup = role.group;
    });
    if (options.removeVariantBar !== false) card.querySelector(".dg-variant-bar")?.remove();
    return card;
  };

  const textareaCard = curateWebviewSpecimen("textarea", new Map([
    ["form-textarea-box", "기본 입력"],
    ["form-textarea-box--data-state-disabled", "비활성"]
  ]));
  textareaCard?.classList.add("dg-curated-specimen--textarea");

  const selectCard = curateWebviewSpecimen("select", new Map([
    ["ui-select", {label: "기본 선택", value: "선택"}],
    ["ui-select--type-bank", {label: "은행 선택", value: "은행 선택"}],
    ["ui-select--type-card", {label: "카드 선택", value: "카드 선택"}]
  ]));
  if (selectCard) {
    selectCard.classList.add("dg-curated-specimen--select");
    const values = new Map([
      ["ui-select", "선택"],
      ["ui-select--type-bank", "은행 선택"],
      ["ui-select--type-card", "카드 선택"]
    ]);
    selectCard.querySelectorAll('.dg-sp-cell[data-dg-variant]:not([hidden])').forEach(cell => {
      const select = cell.querySelector("select");
      const value = values.get(cell.dataset.dgVariant);
      if (!select || !value) return;
      const option = document.createElement("option");
      option.value = value;
      option.textContent = value;
      option.selected = true;
      select.replaceChildren(option);
    });
  }

  const checkboxCard = curateWebviewSpecimen("checkbox", new Map([
    ["form-element-checkbox", "선택 안 함"],
    ["form-element-checkbox--data-align-center", "선택함"]
  ]));
  if (checkboxCard) {
    checkboxCard.classList.add("dg-curated-specimen--checkbox");
    checkboxCard.querySelectorAll('.dg-sp-cell[data-dg-variant]:not([hidden])').forEach(cell => {
      const input = cell.querySelector('input[type="checkbox"]');
      if (!input) return;
      input.checked = cell.dataset.dgVariant.endsWith("--data-align-center");
      input.setAttribute("aria-label", input.checked ? "선택함 예시" : "선택 안 함 예시");
    });
  }

  const radioCard = curateWebviewSpecimen("radio", new Map([
    ["form-element-radio", "선택 안 함"],
    ["form-element-radio--data-size-20", "선택함"]
  ]));
  if (radioCard) {
    radioCard.classList.add("dg-curated-specimen--compact", "dg-curated-specimen--state-pair");
    radioCard.querySelectorAll('.dg-sp-cell[data-dg-variant]:not([hidden])').forEach(cell => {
      const control = cell.querySelector(".form-element-radio");
      const input = cell.querySelector('input[type="radio"]');
      control?.setAttribute("data-size", "20");
      if (!input) return;
      input.checked = cell.dataset.dgVariant.endsWith("--data-size-20");
      input.setAttribute("aria-label", input.checked ? "선택함 예시" : "선택 안 함 예시");
    });
  }

  const switchCard = curateWebviewSpecimen("switch", new Map([
    ["form-element-switch", "꺼짐"],
    ["form-element-switch--data-size-36", "켜짐"]
  ]));
  if (switchCard) {
    switchCard.classList.add("dg-curated-specimen--compact", "dg-curated-specimen--state-pair");
    switchCard.querySelectorAll('.dg-sp-cell[data-dg-variant]:not([hidden])').forEach(cell => {
      const input = cell.querySelector('input[type="checkbox"]');
      if (!input) return;
      input.checked = cell.dataset.dgVariant.endsWith("--data-size-36");
      input.setAttribute("aria-label", input.checked ? "켜짐 예시" : "꺼짐 예시");
    });
  }

  const tabsCard = curateWebviewSpecimen("tabs", new Map([
    ["ui-tab", "기본 탭"]
  ]));
  if (tabsCard) {
    tabsCard.classList.add("dg-curated-specimen--tabs");
    const selectedCell = tabsCard.querySelector('.dg-sp-cell[data-dg-variant="ui-tab"]');
    const buttons = [...(selectedCell?.querySelectorAll(".ui-tab--btn") || [])];
    const panels = [...(selectedCell?.querySelectorAll(".ui-tab--pnl") || [])];
    buttons.forEach((button, index) => {
      button.classList.toggle("selected", index === 0);
      button.setAttribute("aria-selected", String(index === 0));
    });
    panels.forEach((panel, index) => {
      panel.classList.toggle("selected", index === 0);
      panel.setAttribute("aria-hidden", String(index !== 0));
    });
  }

  const statusRoles = new Map([
    ["info-dot-list-item", {label: "기본 안내", group: "notice"}],
    ["info-dot-list-item--data-color-blue", {label: "파란 안내", group: "notice"}],
    ["info-dot-list-item--data-color-green", {label: "초록 안내", group: "notice"}],
    ["ui-square-tag", {label: "사각 태그", group: "tag"}],
    ["ui-round-tag", {label: "라운드 태그", group: "tag"}],
    ["tooltip-gradation-point", {label: "강조 태그", group: "tag"}],
    ["dot-icon-box", {label: "아이콘 표시", group: "icon"}],
    ["tooltip-pink-box", {label: "강조 안내", group: "feedback"}],
    ["tooltip-dark-box", {label: "도움말", group: "feedback"}]
  ]);
  const statusCard = curateWebviewSpecimen("status", statusRoles, {removeVariantBar: false});
  if (statusCard) {
    statusCard.classList.add("dg-curated-specimen--status");
    const stateToggle = statusCard.querySelector(".dg-state-toggle");
    const cardMeta = statusCard.querySelector(".dg-live-card__meta");
    if (stateToggle && cardMeta) cardMeta.append(stateToggle);
    statusCard.querySelector(".dg-variant-bar")?.remove();
    const grid = statusCard.querySelector(".dg-sp-grid");
    const groups = [
      {id: "notice", label: "안내 상태", description: "내용 앞에서 안내 수준을 구분합니다."},
      {id: "tag", label: "상태 태그", description: "짧은 상태값과 강조 정보를 표시합니다."},
      {id: "icon", label: "아이콘 상태", description: "작은 영역에서 상태를 빠르게 구분합니다."},
      {id: "feedback", label: "도움말 · 강조", description: "추가 설명이나 주의가 필요한 내용을 보여줍니다."}
    ];
    groups.forEach(group => {
      const cells = [...statusCard.querySelectorAll(`.dg-sp-cell[data-dg-specimen-group="${group.id}"]`)]
        .filter(cell => !cell.hidden);
      if (!grid || !cells.length) return;
      cells.forEach(cell => {
        if (cell.dataset.dgState && cell.dataset.dgState !== "default") {
          const label = cell.querySelector(".dg-sp-tag");
          if (label) label.textContent += " · 오류";
        }
      });
      const section = document.createElement("section");
      section.className = "dg-status-group";
      section.dataset.statusGroup = group.id;
      const heading = document.createElement("div");
      heading.className = "dg-status-group__heading";
      const title = document.createElement("h4");
      title.textContent = group.label;
      const description = document.createElement("p");
      description.textContent = group.description;
      heading.append(title, description);
      const items = document.createElement("div");
      items.className = "dg-status-group__items";
      cells.forEach(cell => items.append(cell));
      section.append(heading, items);
      grid.append(section);
    });
  }

  const modalCard = guide.querySelector('[data-system-panel="webview"] [data-component-panel="modal"]');
  if (modalCard) {
    modalCard.classList.add("dg-curated-specimen", "dg-curated-specimen--modal");
    modalCard.querySelector(".dg-variant-bar")?.remove();
    const meta = modalCard.querySelector(".dg-live-card__meta");
    if (meta && !meta.querySelector("[data-modal-example]")) {
      const example = document.createElement("span");
      example.dataset.modalExample = "true";
      example.textContent = "주소 검색 예시";
      meta.append(example);
    }
  }

  const moveStateToggleToHeader = card => {
    const stateToggle = card?.querySelector(".dg-state-toggle");
    const cardMeta = card?.querySelector(".dg-live-card__meta");
    if (stateToggle && cardMeta) cardMeta.append(stateToggle);
  };

  const labelSpecimenCell = (cell, label) => {
    const tag = cell.querySelector(".dg-sp-tag");
    if (!tag) return;
    tag.textContent = label;
    tag.title = cell.dataset.dgVariant || "";
    if (cell.dataset.dgState && cell.dataset.dgState !== "default") {
      const stateLabel = cell.dataset.dgState === "disabled" ? "비활성" : "선택";
      tag.textContent += ` · ${stateLabel}`;
    }
  };

  const curateBackofficeCard = (component, roles, modifier) => {
    const card = guide.querySelector(`[data-system-panel="backoffice"] [data-component-panel="${component}"]`);
    if (!card) return null;
    card.classList.add("dg-backoffice-specimen", `dg-backoffice-specimen--${modifier || component}`);
    card.querySelectorAll('.dg-sp-cell[data-dg-variant]').forEach(cell => {
      const role = roles.get(cell.dataset.dgVariant);
      if (!role) {
        cell.hidden = true;
        return;
      }
      labelSpecimenCell(cell, typeof role === "string" ? role : role.label);
      if (typeof role === "object" && role.group) cell.dataset.dgSpecimenGroup = role.group;
    });
    moveStateToggleToHeader(card);
    card.querySelector(".dg-variant-bar")?.remove();
    return card;
  };

  const buildBackofficeGroups = (card, groups) => {
    const grid = card?.querySelector(".dg-sp-grid");
    if (!grid) return;
    groups.forEach(group => {
      const cells = [...card.querySelectorAll(`.dg-sp-cell[data-dg-specimen-group="${group.id}"]`)]
        .filter(cell => !cell.hidden);
      if (!cells.length) return;
      const section = document.createElement("section");
      section.className = "dg-backoffice-group";
      section.dataset.specimenGroup = group.id;
      const heading = document.createElement("div");
      heading.className = "dg-backoffice-group__heading";
      const title = document.createElement("h4");
      title.textContent = group.label;
      const description = document.createElement("p");
      description.textContent = group.description;
      heading.append(title, description);
      const items = document.createElement("div");
      items.className = "dg-backoffice-group__items";
      cells.forEach(cell => items.append(cell));
      section.append(heading, items);
      grid.append(section);
    });
  };

  const prepareWideBackofficeSpecimen = (card, label) => {
    const stage = card?.querySelector(".dg-live-preview__stage");
    if (!stage) return;
    stage.tabIndex = 0;
    stage.setAttribute("role", "region");
    stage.setAttribute("aria-label", `${label}. 좁은 화면에서는 좌우로 이동해 전체 내용을 확인할 수 있습니다.`);
    stage.scrollLeft = 0;
  };

  const backofficeButtonCard = curateBackofficeCard("button", new Map([
    ["bt_bu36", {label: "파란색 채움", group: "filled"}],
    ["bt_bl36", {label: "검정 채움", group: "filled"}],
    ["bt_w36", {label: "테두리 버튼", group: "support"}],
    ["bt_g36", {label: "연회색 채움", group: "support"}],
    ["bt_w26", {label: "작은 버튼", group: "compact"}],
    ["image_add_inner", {label: "이미지 추가", group: "utility"}],
    ["bt_g26", {label: "작은 회색 채움", group: "utility"}]
  ]));
  buildBackofficeGroups(backofficeButtonCard, [
    {id: "filled", label: "채움 버튼", description: "색으로 행동을 강조하는 36px 버튼입니다."},
    {id: "support", label: "보조 버튼", description: "테두리와 연회색 표면으로 보조 행동을 구분합니다."},
    {id: "compact", label: "작은 버튼", description: "표와 좁은 영역에서 쓰는 26px 버튼입니다."},
    {id: "utility", label: "도구 버튼", description: "이미지 추가와 좁은 영역의 보조 행동에 사용하는 버튼입니다."}
  ]);

  curateBackofficeCard("text-field", new Map([
    ["input_text_style", "기본 입력"],
    ["calendar_control", "날짜 입력"]
  ]));

  curateBackofficeCard("checkbox", new Map([
    ["input_cb", "선택 안 함"],
    ["input_cb--type-checkbox", "선택함"]
  ]));

  const backofficeSwitchCard = curateBackofficeCard("switch", new Map([
    ["check_switch", "꺼짐 · 기본 크기"],
    ["check_switch--check_lg", "켜짐 · 큰 크기"]
  ]));
  backofficeSwitchCard?.querySelectorAll('.dg-sp-cell[data-dg-variant]').forEach(cell => {
    const input = cell.querySelector('input[type="checkbox"]');
    if (!input) return;
    input.checked = cell.dataset.dgVariant === "check_switch--check_lg";
    input.setAttribute("aria-label", input.checked ? "켜짐 예시" : "꺼짐 예시");
  });

  curateBackofficeCard("tabs", new Map([
    ["tabs_header", "기본 탭"],
    ["tabs_header--type2", "테두리 탭"],
    ["tabs_header--type3", "분할 탭"]
  ]));

  const backofficePaginationCard = curateBackofficeCard("pagination", new Map([
    ["paging_wrap", "10페이지 이동"]
  ]));
  if (backofficePaginationCard) {
    const meta = backofficePaginationCard.querySelector(".dg-live-card__meta");
    const note = document.createElement("span");
    note.className = "dg-wide-specimen-note";
    note.textContent = "좁은 화면에서는 좌우로 이동";
    meta?.append(note);
    prepareWideBackofficeSpecimen(backofficePaginationCard, "페이지네이션 미리보기");
  }

  const backofficeModalCard = guide.querySelector('[data-system-panel="backoffice"] [data-component-panel="modal"]');
  if (backofficeModalCard) {
    backofficeModalCard.classList.add("dg-backoffice-specimen", "dg-backoffice-specimen--modal");
    backofficeModalCard.querySelector(".dg-variant-bar")?.remove();
    const meta = backofficeModalCard.querySelector(".dg-live-card__meta");
    const note = document.createElement("span");
    note.className = "dg-wide-specimen-note";
    note.textContent = "데스크톱 원본 너비";
    meta?.append(note);
    prepareWideBackofficeSpecimen(backofficeModalCard, "모달 미리보기");
  }

  const backofficeTableCard = curateBackofficeCard("table", new Map([
    ["tbl_list_new", "항목 · 값 표"],
    ["tbl_list", "편집형 목록 표"]
  ]));
  if (backofficeTableCard) {
    const meta = backofficeTableCard.querySelector(".dg-live-card__meta");
    const note = document.createElement("span");
    note.className = "dg-wide-specimen-note";
    note.textContent = "좁은 화면에서는 표만 좌우로 이동";
    meta?.append(note);
    prepareWideBackofficeSpecimen(backofficeTableCard, "표 미리보기");
  }

  const secondarySystems = new Set(["online-pg", "saleoffice", "lspnoffice", "portal"]);

  const secondaryPanel = system => guide.querySelector(`[data-system-panel="${system}"]`);

  const secondaryCard = (system, component) =>
    secondaryPanel(system)?.querySelector(`[data-component-panel="${component}"]`);

  const setSecondaryComponentCopy = (system, component, label, description, categoryLabel) => {
    const panel = secondaryPanel(system);
    const card = secondaryCard(system, component);
    const button = panel?.querySelector(`[data-component-select="${component}"]`);
    const heading = card?.querySelector("header > div");
    if (card?.querySelector("h3")) card.querySelector("h3").textContent = label;
    let descriptionElement = card?.querySelector(".dg-live-card__description");
    if (!descriptionElement && heading && description) {
      descriptionElement = document.createElement("p");
      descriptionElement.className = "dg-live-card__description";
      heading.append(descriptionElement);
    }
    if (descriptionElement) descriptionElement.textContent = description;
    if (categoryLabel && card?.querySelector(".dg-live-card__kind")) {
      card.querySelector(".dg-live-card__kind").textContent = categoryLabel;
    }
    if (button?.querySelector("strong")) button.querySelector("strong").textContent = label;
    if (categoryLabel && button?.querySelector("span")) button.querySelector("span").textContent = categoryLabel;
  };

  const hideSecondaryComponent = (system, component) => {
    const panel = secondaryPanel(system);
    const card = secondaryCard(system, component);
    const button = panel?.querySelector(`[data-component-select="${component}"]`);
    if (card) {
      card.hidden = true;
      card.style.setProperty("display", "none", "important");
    }
    if (button) {
      button.hidden = true;
      button.style.setProperty("display", "none", "important");
    }
  };

  const curateSecondaryCard = (system, component, roles, modifier) => {
    if (!secondarySystems.has(system)) return null;
    const card = secondaryCard(system, component);
    if (!card) return null;
    card.classList.add("dg-secondary-specimen", `dg-secondary-specimen--${modifier || component}`,
      `dg-secondary-specimen--${system}`);
    const seen = new Set();
    card.querySelectorAll('.dg-sp-cell[data-dg-variant]').forEach(cell => {
      const role = roles.get(cell.dataset.dgVariant);
      const key = `${cell.dataset.dgVariant}:${cell.dataset.dgState || "default"}`;
      if (!role || seen.has(key)) {
        cell.hidden = true;
        cell.style.setProperty("display", "none", "important");
        return;
      }
      seen.add(key);
      labelSpecimenCell(cell, typeof role === "string" ? role : role.label);
      if (typeof role === "object" && role.group) cell.dataset.dgSpecimenGroup = role.group;
    });
    moveStateToggleToHeader(card);
    card.querySelector(".dg-variant-bar")?.remove();
    return card;
  };

  const buildSecondaryGroups = (card, groups) => {
    const grid = card?.querySelector(".dg-sp-grid");
    if (!grid) return;
    groups.forEach(group => {
      const cells = [...card.querySelectorAll(`.dg-sp-cell[data-dg-specimen-group="${group.id}"]`)]
        .filter(cell => !cell.hidden);
      if (!cells.length) return;
      const section = document.createElement("section");
      section.className = "dg-secondary-group";
      section.dataset.specimenGroup = group.id;
      const heading = document.createElement("div");
      heading.className = "dg-secondary-group__heading";
      const title = document.createElement("h4");
      title.textContent = group.label;
      const description = document.createElement("p");
      description.textContent = group.description;
      heading.append(title, description);
      const items = document.createElement("div");
      items.className = "dg-secondary-group__items";
      cells.forEach(cell => items.append(cell));
      section.append(heading, items);
      grid.append(section);
    });
  };

  const prepareSecondaryWide = (card, label, noteText = "좁은 화면에서는 표만 좌우로 이동") => {
    const stage = card?.querySelector(".dg-live-preview__stage");
    if (!stage) return;
    stage.tabIndex = 0;
    stage.setAttribute("role", "region");
    stage.setAttribute("aria-label", `${label}. 좁은 화면에서는 좌우로 이동해 전체 내용을 확인할 수 있습니다.`);
    stage.scrollLeft = 0;
    const meta = card.querySelector(".dg-live-card__meta");
    if (meta && !meta.querySelector(".dg-wide-specimen-note")) {
      const note = document.createElement("span");
      note.className = "dg-wide-specimen-note";
      note.textContent = noteText;
      meta.append(note);
    }
  };

  const curateCompleteModal = (system, label, description) => {
    const card = secondaryCard(system, "modal");
    if (!card) return null;
    card.classList.add("dg-secondary-specimen", "dg-secondary-complete-modal", `dg-secondary-complete-modal--${system}`);
    card.querySelector(".dg-variant-bar")?.remove();
    setSecondaryComponentCopy(system, "modal", label, description, "모달");
    const meta = card.querySelector(".dg-live-card__meta");
    if (meta && !meta.querySelector("[data-complete-modal-example]")) {
      const example = document.createElement("span");
      example.dataset.completeModalExample = "true";
      example.textContent = "열린 상태 예시";
      meta.append(example);
    }
    return card;
  };

  setSecondaryComponentCopy("online-pg", "button", "버튼", "서비스 행동과 테스트 도구를 용도별로 나눠 비교합니다.", "버튼");
  const onlineButtonCard = curateSecondaryCard("online-pg", "button", new Map([
    ["btn-base", {label: "인증번호 요청", group: "service"}],
    ["btn-secondary", {label: "테스트 보조", group: "test"}],
    ["btn-primary", {label: "테스트 주요", group: "test"}]
  ]));
  buildSecondaryGroups(onlineButtonCard, [
    {id: "service", label: "서비스 행동", description: "실제 서비스 흐름에서 사용하는 요청 버튼입니다."},
    {id: "test", label: "테스트 도구", description: "결제 테스트 화면 전용이며 일반 화면의 버튼 역할을 대신하지 않습니다."}
  ]);
  setSecondaryComponentCopy("online-pg", "textarea", "가맹점 번호 입력", "가맹점을 선택하거나 번호를 직접 입력하는 결합 입력입니다.", "입력");
  curateSecondaryCard("online-pg", "textarea", new Map([["form-group", "가맹점 선택 · 번호 입력"]]), "form-group");
  curateCompleteModal("online-pg", "약관 확인 모달", "약관 제목과 본문, 확인 행동이 한 화면에 갖춰진 열린 상태입니다.");

  setSecondaryComponentCopy("saleoffice", "button", "버튼", "강조 수준과 아이콘 용도에 따라 대표 버튼만 비교합니다.", "버튼");
  const saleButtonCard = curateSecondaryCard("saleoffice", "button", new Map([
    ["bt_bu36", {label: "파란색 채움", group: "filled"}],
    ["bt_bl36", {label: "검정 채움", group: "filled"}],
    ["bt_w36", {label: "테두리 버튼", group: "support"}],
    ["bt_g36", {label: "연회색 채움", group: "support"}],
    ["bt_g26", {label: "작은 버튼", group: "compact"}],
    ["bt_g36--ic_more", {label: "다음", group: "icon"}],
    ["bt_bu36--ic_src", {label: "검색", group: "icon"}],
    ["bt_w36--ic_arrdown", {label: "펼침", group: "icon"}],
    ["bt_w36--ic_grp", {label: "그룹", group: "icon"}],
    ["bt_g30--ic_download", {label: "다운로드", group: "icon"}]
  ]));
  buildSecondaryGroups(saleButtonCard, [
    {id: "filled", label: "채움 버튼", description: "색으로 주요 행동을 강조합니다."},
    {id: "support", label: "보조 버튼", description: "테두리와 연회색 표면으로 행동의 위계를 낮춥니다."},
    {id: "compact", label: "작은 버튼", description: "표와 좁은 영역에 사용하는 버튼입니다."},
    {id: "icon", label: "아이콘 버튼", description: "검색, 펼침, 다운로드처럼 결과가 명확한 행동입니다."}
  ]);
  setSecondaryComponentCopy("saleoffice", "text-field", "입력 필드", "버튼 포함 입력과 날짜 입력을 대표 형태로 비교합니다.", "입력");
  curateSecondaryCard("saleoffice", "text-field", new Map([
    ["input_text_style", "버튼 포함 입력"],
    ["calendar_control", "날짜 입력"]
  ]));
  const saleSelectCard = secondaryCard("saleoffice", "select");
  const saleCheckboxCard = secondaryCard("saleoffice", "checkbox");
  const saleSelectCell = saleSelectCard?.querySelector('.dg-sp-cell[data-dg-variant="ui-select-all"]');
  const saleCheckboxGrid = saleCheckboxCard?.querySelector(".dg-sp-grid");
  if (saleSelectCell && saleCheckboxGrid) saleCheckboxGrid.append(saleSelectCell);
  hideSecondaryComponent("saleoffice", "select");
  setSecondaryComponentCopy("saleoffice", "checkbox", "선택 컨트롤", "라디오와 체크박스를 선택 방식별로 비교합니다.", "선택");
  const saleCheckbox = curateSecondaryCard("saleoffice", "checkbox", new Map([
    ["chkBox_wrap", {label: "고객 구분", group: "radio"}],
    ["input_cb", {label: "선택 안 함", group: "check"}],
    ["ui-select-all", {label: "전체 선택", group: "check"}]
  ]), "selection");
  saleCheckbox?.querySelectorAll('input[type="checkbox"]').forEach(input => { input.checked = false; });
  const saleAll = saleCheckbox?.querySelector('.dg-sp-cell[data-dg-variant="ui-select-all"] input[type="checkbox"]');
  if (saleAll) saleAll.checked = true;
  buildSecondaryGroups(saleCheckbox, [
    {id: "radio", label: "단일 선택", description: "여러 항목 중 하나를 고르는 라디오 그룹입니다."},
    {id: "check", label: "체크 선택", description: "개별 선택과 전체 선택 상태를 비교합니다."}
  ]);
  hideSecondaryComponent("saleoffice", "tabs");
  hideSecondaryComponent("saleoffice", "modal");
  setSecondaryComponentCopy("saleoffice", "table", "표", "항목·값 표와 목록 표의 실제 열 구성을 유지합니다.", "데이터 표시");
  const saleTable = curateSecondaryCard("saleoffice", "table", new Map([
    ["tbl_list_new", "항목 · 값 표"],
    ["tbl_list", "목록 표"]
  ]));
  prepareSecondaryWide(saleTable, "판매대행점 표 미리보기");

  setSecondaryComponentCopy("lspnoffice", "button", "버튼", "조회, 화면 행동, 아이콘 행동의 대표 형태를 비교합니다.", "버튼");
  const lspnButtonCard = curateSecondaryCard("lspnoffice", "button", new Map([
    ["history-search__btn", {label: "조회", group: "search"}],
    ["history-search__btn--reset", {label: "Excel", group: "search"}],
    ["bt_bu36", {label: "목록으로", group: "action"}],
    ["detail-actions__btn", {label: "카드 발급", group: "action"}],
    ["bt_bu36--ic_src", {label: "검색 아이콘", group: "icon"}]
  ]));
  buildSecondaryGroups(lspnButtonCard, [
    {id: "search", label: "조회 · 내보내기", description: "조건 조회와 결과 내보내기 행동입니다."},
    {id: "action", label: "화면 행동", description: "이동과 발급처럼 화면 흐름을 바꾸는 행동입니다."},
    {id: "icon", label: "아이콘 버튼", description: "검색 의미를 아이콘과 함께 전달합니다."}
  ]);
  hideSecondaryComponent("lspnoffice", "pagination");
  hideSecondaryComponent("lspnoffice", "modal");
  setSecondaryComponentCopy("lspnoffice", "table", "표", "사용 이력과 카드 정보를 실제 열 너비로 비교합니다.", "데이터 표시");
  const lspnTable = curateSecondaryCard("lspnoffice", "table", new Map([
    ["history-table", "사용 이력 표"],
    ["step2-info-table", "카드 정보 표"]
  ]));
  prepareSecondaryWide(lspnTable, "고유가피해지원금 표 미리보기");

  setSecondaryComponentCopy("portal", "button", "앱 다운로드 버튼", "스토어 이동에 사용하는 대표 버튼입니다.", "버튼");
  curateSecondaryCard("portal", "button", new Map([["btn-base", "앱 다운로드"]]));
  setSecondaryComponentCopy("portal", "text-field", "입력 필드", "기본 입력과 강조 테두리 입력을 비교합니다.", "입력");
  curateSecondaryCard("portal", "text-field", new Map([
    ["form-input-box", "기본 입력"],
    ["form-input-box--data-line-color-black", "강조 테두리"]
  ]));
  setSecondaryComponentCopy("portal", "checkbox", "체크박스", "선택 전과 선택 후 상태를 실제 라벨과 함께 비교합니다.", "선택");
  const portalCheckbox = curateSecondaryCard("portal", "checkbox", new Map([
    ["form-element-checkbox", "선택 안 함"],
    ["form-element-checkbox--data-align-center", "선택함"]
  ]), "selection");
  portalCheckbox?.querySelectorAll('input[type="checkbox"]').forEach((input, index) => {
    input.checked = index === 1;
    input.setAttribute("aria-label", index === 1 ? "선택함 예시" : "선택 안 함 예시");
  });
  setSecondaryComponentCopy("portal", "pagination", "페이지 이동", "페이지 번호와 앞뒤 이동을 한 줄로 확인합니다.", "탐색");
  const portalPagination = curateSecondaryCard("portal", "pagination", new Map([["site-paging", "페이지 이동"]]));
  prepareSecondaryWide(portalPagination, "전용포탈 페이지 이동 미리보기", "좁은 화면에서는 페이지 이동을 좌우로 이동");
  const portalModal = curateCompleteModal("portal", "메인 공지 모달", "공지 제목과 본문, 다시 보지 않기, 닫기 행동을 갖춘 열린 상태입니다.");
  const portalModalTitle = portalModal?.querySelector("#molMain02_ntcTtl");
  const portalModalContent = portalModal?.querySelector("#molMain02_ntcConts");
  if (portalModalTitle && !portalModalTitle.textContent.trim()) portalModalTitle.textContent = "전용포탈 이용 안내";
  if (portalModalContent && !portalModalContent.textContent.trim()) {
    portalModalContent.textContent = "서비스 점검 시간과 주요 이용 정보를 확인해 주세요.";
  }
  prepareSecondaryWide(portalModal, "전용포탈 메인 공지 모달", "좁은 화면에서는 넓은 모달을 좌우로 이동");

  const resizePreview = preview => {
    const stage = preview.querySelector(".dg-live-preview__stage");
    const canvas = preview.querySelector(".dg-live-preview__canvas");
    const naturalWidth = Number(preview.dataset.naturalWidth);
    const naturalHeight = Number(preview.dataset.naturalHeight);
    if (!stage || !canvas || !naturalWidth || !naturalHeight || stage.clientWidth === 0) return;
    if (preview.closest(".dg-live-card--component")) {
      canvas.style.width = "100%";
      canvas.style.height = "auto";
      stage.style.height = "auto";
      canvas.style.transform = "none";
      canvas.style.marginLeft = "0";
      const scope = canvas.querySelector(":scope > .dg-render-scope");
      const scopeRect = scope?.getBoundingClientRect();
      const visibleCells = [...(scope?.querySelectorAll(".dg-sp-cell") || [])]
        .filter(cell => getComputedStyle(cell).display !== "none");
      const cellBottom = scopeRect ? visibleCells.reduce((bottom, cell) =>
        Math.max(bottom, cell.getBoundingClientRect().bottom - scopeRect.top), 0) : 0;
      const renderHeight = Math.max(88, scope?.scrollHeight || 0, Math.ceil(cellBottom));
      const stageStyle = getComputedStyle(stage);
      const stageChrome = [stageStyle.paddingTop, stageStyle.paddingBottom,
        stageStyle.borderTopWidth, stageStyle.borderBottomWidth]
        .reduce((sum, value) => sum + (Number.parseFloat(value) || 0), 0);
      canvas.style.height = `${renderHeight}px`;
      stage.style.height = `${Math.ceil(renderHeight + stageChrome)}px`;
      return;
    }
    const renderHeight = naturalHeight;
    const webviewLayout = preview.closest('[data-system-panel="webview"]')
      ?.querySelector('[data-guide-panel="layouts"]')?.contains(preview);
    const previewWidth = webviewLayout ? Math.min(stage.clientWidth, 320) : stage.clientWidth;
    const scale = Math.min(1, previewWidth / naturalWidth);
    canvas.style.transform = `scale(${scale})`;
    canvas.style.marginLeft = `${Math.max(0, (stage.clientWidth - naturalWidth * scale) / 2)}px`;
    stage.style.height = `${Math.ceil(renderHeight * scale)}px`;
  };

  const resizeVisiblePreviews = () => {
    guide.querySelectorAll(".dg-live-preview").forEach(resizePreview);
  };

  const syncStyleVariants = (system, styleId) => {
    const panel = guide.querySelector(`[data-system-panel="${CSS.escape(system)}"]`);
    panel?.querySelectorAll("[data-style-variant]").forEach(variant => {
      variant.hidden = variant.dataset.styleVariant !== styleId;
    });
  };

  const applyStyle = system => {
    const selector = guide.querySelector(`[data-system-facet="${CSS.escape(system)}"] select`);
    const option = selector?.selectedOptions?.[0];
    if (styleLink && option?.dataset.styleHref) {
      styleLink.href = option.dataset.styleHref;
      syncStyleVariants(system, option.value);
    }
  };

  const ensureLayoutStyle = systemPanel => {
    const variants = [...systemPanel.querySelectorAll("[data-style-variant]")];
    if (!variants.length) return;
    const system = systemPanel.dataset.systemPanel;
    const selector = guide.querySelector(`[data-system-facet="${CSS.escape(system)}"] select`);
    if (!selector || variants.some(variant => variant.dataset.styleVariant === selector.value)) return;
    const firstStyle = variants[0].dataset.styleVariant;
    if ([...selector.options].some(option => option.value === firstStyle)) {
      selector.value = firstStyle;
      applyStyle(system);
    }
  };

  const showSystem = system => {
    systemButtons.forEach(button => {
      const active = button.dataset.system === system;
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-selected", String(active));
    });
    panels.forEach(panel => {
      panel.hidden = panel.dataset.systemPanel !== system;
    });
    facetSelectors.forEach(selector => {
      selector.hidden = selector.dataset.systemFacet !== system;
    });
    applyStyle(system);
    requestAnimationFrame(resizeVisiblePreviews);
  };

  const showTab = (systemPanel, tab) => {
    systemPanel.querySelectorAll("[data-guide-tab]").forEach(button => {
      const active = button.dataset.guideTab === tab;
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-selected", String(active));
    });
    systemPanel.querySelectorAll("[data-guide-panel]").forEach(panel => {
      panel.hidden = panel.dataset.guidePanel !== tab;
    });
    if (tab === "layouts") ensureLayoutStyle(systemPanel);
    requestAnimationFrame(resizeVisiblePreviews);
  };

  const setupComponentWorkspace = systemPanel => {
    const workspace = systemPanel.querySelector(".dg-component-workspace");
    if (!workspace) return;
    const buttons = [...workspace.querySelectorAll("[data-component-select]")];
    const cards = [...workspace.querySelectorAll("[data-component-panel]")];
    const select = component => {
      buttons.forEach(button => {
        const active = button.dataset.componentSelect === component;
        button.classList.toggle("is-active", active);
        button.setAttribute("aria-selected", String(active));
      });
      cards.forEach(card => card.classList.toggle("is-active", card.dataset.componentPanel === component));
      requestAnimationFrame(resizeVisiblePreviews);
    };
    buttons.forEach(button => button.addEventListener("click", () => select(button.dataset.componentSelect)));
    workspace.classList.add("is-ready");
    select(buttons[0]?.dataset.componentSelect);
  };

  const setupStateToggles = systemPanel => {
    systemPanel.querySelectorAll("[data-state-toggle]").forEach(button => {
      const card = button.closest(".dg-live-card--component");
      if (!card) return;
      button.addEventListener("click", () => {
        const expanded = card.classList.toggle("is-state-expanded");
        button.setAttribute("aria-pressed", String(expanded));
        button.textContent = expanded ? "기본 상태만 보기" : "상태 함께 보기";
        requestAnimationFrame(() => resizePreview(card.querySelector(".dg-live-preview")));
      });
    });
  };

  systemButtons.forEach(button => button.addEventListener("click", () => {
    showSystem(button.dataset.system);
    history.replaceState(null, "", `#${button.dataset.system}`);
  }));

  panels.forEach(panel => panel.querySelectorAll("[data-guide-tab]").forEach(button => {
    button.addEventListener("click", () => {
      showTab(panel, button.dataset.guideTab);
      history.replaceState(null, "", `#${panel.dataset.systemPanel}/${button.dataset.guideTab}`);
    });
  }));
  panels.forEach(setupComponentWorkspace);
  panels.forEach(setupStateToggles);

  facetSelectors.forEach(selector => selector.querySelector("select")?.addEventListener("change", () => {
    applyStyle(selector.dataset.systemFacet);
  }));
  styleLink?.addEventListener("load", () => requestAnimationFrame(resizeVisiblePreviews));

  guide.addEventListener("submit", event => {
    if (event.target.closest(".dg-render-scope")) event.preventDefault();
  });
  guide.addEventListener("click", event => {
    if (event.target.closest(".dg-render-scope a")) event.preventDefault();
  });

  const hash = /^#([^/]+)(?:\/([^/]+))?$/.exec(location.hash);
  const system = hash && systemButtons.some(button => button.dataset.system === hash[1])
    ? hash[1] : systemButtons[0]?.dataset.system;
  if (!system) return;
  showSystem(system);
  if (hash?.[2]) {
    const panel = guide.querySelector(`[data-system-panel="${CSS.escape(system)}"]`);
    if (panel?.querySelector(`[data-guide-tab="${CSS.escape(hash[2])}"]`)) {
      showTab(panel, hash[2]);
    }
  }

  const previewObserver = new ResizeObserver(entries => entries.forEach(entry => resizePreview(entry.target)));
  guide.querySelectorAll(".dg-live-preview").forEach(preview => previewObserver.observe(preview));
  resizeVisiblePreviews();
})();
