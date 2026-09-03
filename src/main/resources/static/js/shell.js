(() => {
  const openPopupSelector = "details.pop[open]";
  const submitLoadingSelector = "button[data-submit-loading]";
  const listLoadingRegionSelector = "[data-list-loading-region]";
  const listLoadingTriggerSelector = "form[data-list-loading-trigger]";
  const listLoadingLinksSelector = "[data-list-loading-links] a";
  const pageLoadingOverlaySelector = "[data-page-loading-overlay]";
  const navToggleSelector = "[data-nav-toggle]";
  const projectSwitchSelector = "[data-project-switch]";
  const projectSwitchTriggerSelector = "[data-project-switch-trigger]";
  const projectSwitchOptionSelector = "[data-project-switch-option]";
  const fieldSelectSelector = "select.field__control:not([data-field-select-native])";
  const fieldSelectRootSelector = "[data-field-select]";
  const fieldSelectTriggerSelector = "[data-field-select-trigger]";
  const fieldSelectOptionSelector = "[data-field-select-option]";
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

  function fieldSelectOptions(root) {
    return Array.from(root.querySelectorAll(fieldSelectOptionSelector));
  }

  function refreshFieldSelect(root) {
    const select = root.querySelector("select");
    const list = root.querySelector("[role='listbox']");
    if (!select || !list) return;

    list.replaceChildren(...Array.from(select.options).map((nativeOption, index) => {
      const option = document.createElement("div");
      option.className = "field-select__option";
      option.id = `${select.id || "field-select"}-option-${index}`;
      option.setAttribute("role", "option");
      option.setAttribute("tabindex", "-1");
      option.dataset.fieldSelectOption = "";
      option.dataset.value = nativeOption.value;
      option.setAttribute("aria-selected", String(nativeOption.selected));
      if (nativeOption.disabled) option.setAttribute("aria-disabled", "true");
      option.textContent = nativeOption.textContent;
      return option;
    }));

    syncFieldSelect(root);
  }

  function syncFieldSelect(root) {
    const select = root.querySelector("select");
    const trigger = root.querySelector(fieldSelectTriggerSelector);
    const value = root.querySelector("[data-field-select-value]");
    if (!select || !trigger || !value) return;

    const selected = select.options[select.selectedIndex];
    value.textContent = selected?.textContent || "선택하세요";
    trigger.disabled = select.disabled;
    fieldSelectOptions(root).forEach((option) => {
      option.setAttribute("aria-selected", String(option.dataset.value === select.value));
    });
  }

  function openFieldSelect(root, open) {
    const trigger = root.querySelector(fieldSelectTriggerSelector);
    const list = root.querySelector("[role='listbox']");
    if (!trigger || !list || trigger.disabled) return;
    trigger.setAttribute("aria-expanded", String(open));
    list.hidden = !open;
    if (open) {
      const selected = fieldSelectOptions(root).findIndex((option) => option.getAttribute("aria-selected") === "true");
      const active = selected >= 0 ? selected : 0;
      const option = fieldSelectOptions(root)[active];
      if (option) {
        trigger.setAttribute("aria-activedescendant", option.id);
        option.setAttribute("data-highlighted", "");
      }
    } else {
      trigger.removeAttribute("aria-activedescendant");
      fieldSelectOptions(root).forEach((option) => option.removeAttribute("data-highlighted"));
    }
  }

  function closeOpenFieldSelects(except) {
    document.querySelectorAll(`${fieldSelectRootSelector} ${fieldSelectTriggerSelector}[aria-expanded='true']`)
      .forEach((openTrigger) => {
        const root = openTrigger.closest(fieldSelectRootSelector);
        if (root !== except) openFieldSelect(root, false);
      });
  }

  function enhanceFieldSelect(select) {
    if (!(select instanceof HTMLSelectElement) || select.matches("[data-field-select-native]")
        || select.closest(fieldSelectRootSelector)) return;

    const wrapper = document.createElement("div");
    wrapper.className = "field-select";
    wrapper.dataset.fieldSelect = "";
    const trigger = document.createElement("button");
    trigger.className = "field-select__trigger";
    trigger.type = "button";
    trigger.dataset.fieldSelectTrigger = "";
    trigger.setAttribute("aria-haspopup", "listbox");
    trigger.setAttribute("aria-expanded", "false");
    trigger.setAttribute("aria-controls", `${select.id || "field-select"}-list`);
    const label = select.id ? document.querySelector(`label[for='${CSS.escape(select.id)}']`) : null;
    if (label) trigger.setAttribute("aria-label", label.textContent.trim());
    trigger.innerHTML = '<span data-field-select-value></span><svg class="field-select__chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m7 10 5 5 5-5"/></svg>';

    const list = document.createElement("div");
    list.className = "field-select__list";
    list.id = `${select.id || "field-select"}-list`;
    list.dataset.fieldSelectList = "";
    list.setAttribute("role", "listbox");
    list.setAttribute("tabindex", "-1");
    list.hidden = true;

    select.dataset.fieldSelectNative = "";
    select.tabIndex = -1;
    select.parentNode.insertBefore(wrapper, select);
    wrapper.append(trigger, list, select);
    refreshFieldSelect(wrapper);
    select.addEventListener("change", () => syncFieldSelect(wrapper));

    const observer = new MutationObserver(() => refreshFieldSelect(wrapper));
    observer.observe(select, { childList: true, subtree: true });
  }

  function initializeFieldSelects() {
    document.querySelectorAll(fieldSelectSelector).forEach(enhanceFieldSelect);
    const observer = new MutationObserver((records) => {
      records.flatMap((record) => Array.from(record.addedNodes))
        .filter((node) => node.nodeType === Node.ELEMENT_NODE)
        .forEach((node) => {
          if (node.matches?.(fieldSelectSelector)) enhanceFieldSelect(node);
          node.querySelectorAll?.(fieldSelectSelector).forEach(enhanceFieldSelect);
        });
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  document.addEventListener("DOMContentLoaded", initializeFieldSelects);

  function projectSwitchOptions(root) {
    return Array.from(root.querySelectorAll(projectSwitchOptionSelector));
  }

  function highlightProjectOption(root, index) {
    const options = projectSwitchOptions(root);
    if (!options.length) return;

    const nextIndex = (index + options.length) % options.length;
    options.forEach((option, optionIndex) => {
      option.toggleAttribute("data-highlighted", optionIndex === nextIndex);
    });
    const trigger = root.querySelector(projectSwitchTriggerSelector);
    trigger?.setAttribute("aria-activedescendant", options[nextIndex].id);
    options[nextIndex].scrollIntoView({ block: "nearest" });
  }

  function openProjectSwitch(root, open) {
    const trigger = root.querySelector(projectSwitchTriggerSelector);
    const list = root.querySelector("[role='listbox']");
    if (!trigger || !list) return;

    trigger.setAttribute("aria-expanded", String(open));
    list.hidden = !open;
    if (open) {
      const options = projectSwitchOptions(root);
      const selectedIndex = options.findIndex((option) => option.getAttribute("aria-selected") === "true");
      highlightProjectOption(root, selectedIndex >= 0 ? selectedIndex : 0);
    } else {
      trigger.removeAttribute("aria-activedescendant");
      root.querySelectorAll(`${projectSwitchOptionSelector}[data-highlighted]`)
        .forEach((option) => option.removeAttribute("data-highlighted"));
    }
  }

  function chooseProject(root, option) {
    const url = option?.dataset.projectUrl;
    if (!url) return;
    window.location.assign(url);
  }

  document.addEventListener("click", (event) => {
    const fieldTrigger = event.target.closest?.(fieldSelectTriggerSelector);
    const fieldOption = event.target.closest?.(fieldSelectOptionSelector);
    const fieldRoot = event.target.closest?.(fieldSelectRootSelector);
    if (fieldOption && fieldRoot && fieldOption.getAttribute("aria-disabled") !== "true") {
      const select = fieldRoot.querySelector("select");
      if (select) {
        select.value = fieldOption.dataset.value;
        select.dispatchEvent(new Event("change", { bubbles: true }));
      }
      openFieldSelect(fieldRoot, false);
      fieldRoot.querySelector(fieldSelectTriggerSelector)?.focus();
      return;
    }
    if (fieldTrigger) {
      const expanded = fieldTrigger.getAttribute("aria-expanded") === "true";
      const root = fieldTrigger.closest(fieldSelectRootSelector);
      if (!expanded) closeOpenFieldSelects(root);
      openFieldSelect(root, !expanded);
      return;
    }
    const trigger = event.target.closest?.(projectSwitchTriggerSelector);
    const option = event.target.closest?.(projectSwitchOptionSelector);
    const root = event.target.closest?.(projectSwitchSelector);

    if (option && root) {
      chooseProject(root, option);
      return;
    }
    if (trigger) {
      const expanded = trigger.getAttribute("aria-expanded") === "true";
      openProjectSwitch(trigger.closest(projectSwitchSelector), !expanded);
      return;
    }
    document.querySelectorAll(`${projectSwitchSelector} ${projectSwitchTriggerSelector}[aria-expanded='true']`)
      .forEach((openTrigger) => openProjectSwitch(openTrigger.closest(projectSwitchSelector), false));
    document.querySelectorAll(`${fieldSelectRootSelector} ${fieldSelectTriggerSelector}[aria-expanded='true']`)
      .forEach((openTrigger) => openFieldSelect(openTrigger.closest(fieldSelectRootSelector), false));
  });

  document.addEventListener("keydown", (event) => {
    const fieldTrigger = event.target.closest?.(fieldSelectTriggerSelector);
    if (fieldTrigger) {
      const root = fieldTrigger.closest(fieldSelectRootSelector);
      const options = fieldSelectOptions(root);
      const expanded = fieldTrigger.getAttribute("aria-expanded") === "true";
      const activeIndex = Math.max(0, options.findIndex((option) => option.hasAttribute("data-highlighted")));
      if (event.key === "Escape" && expanded) {
        event.preventDefault();
        openFieldSelect(root, false);
        return;
      }
      if ((event.key === "ArrowDown" || event.key === "ArrowUp") && options.length) {
        event.preventDefault();
        if (!expanded) openFieldSelect(root, true);
        else {
          const next = (activeIndex + (event.key === "ArrowDown" ? 1 : -1) + options.length) % options.length;
          options.forEach((option, index) => option.toggleAttribute("data-highlighted", index === next));
          fieldTrigger.setAttribute("aria-activedescendant", options[next].id);
          options[next].scrollIntoView({ block: "nearest" });
        }
        return;
      }
      if ((event.key === "Enter" || event.key === " ") && !expanded) {
        event.preventDefault();
        openFieldSelect(root, true);
        return;
      }
      if ((event.key === "Enter" || event.key === " ") && expanded && options.length) {
        event.preventDefault();
        const option = options[activeIndex];
        if (option) option.click();
        return;
      }
    }
    const trigger = event.target.closest?.(projectSwitchTriggerSelector);
    if (!trigger) return;

    const root = trigger.closest(projectSwitchSelector);
    const options = projectSwitchOptions(root);
    const expanded = trigger.getAttribute("aria-expanded") === "true";
    const activeIndex = Math.max(0, options.findIndex((option) => option.hasAttribute("data-highlighted")));

    if (event.key === "Escape" && expanded) {
      event.preventDefault();
      openProjectSwitch(root, false);
      return;
    }
    if ((event.key === "ArrowDown" || event.key === "ArrowUp") && options.length) {
      event.preventDefault();
      if (!expanded) openProjectSwitch(root, true);
      else highlightProjectOption(root, activeIndex + (event.key === "ArrowDown" ? 1 : -1));
      return;
    }
    if ((event.key === "Enter" || event.key === " ") && !expanded) {
      event.preventDefault();
      openProjectSwitch(root, true);
      return;
    }
    if ((event.key === "Enter" || event.key === " ") && expanded && options.length) {
      event.preventDefault();
      chooseProject(root, options[activeIndex]);
    }
  });

  function closeHeaderPopups(except) {
    document.querySelectorAll(openPopupSelector).forEach((popup) => {
      if (popup !== except) popup.removeAttribute("open");
    });
  }

  document.addEventListener("click", (event) => {
    const currentPopup = event.target.closest?.("details.pop") ?? null;
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
    form.dataset.submitting = "true";
    form.setAttribute("aria-busy", "true");
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
    if (!(form instanceof HTMLFormElement) || event.defaultPrevented) return;
    if (form.dataset.submitting === "true") {
      event.preventDefault();
      return;
    }

    if (form.matches(listLoadingTriggerSelector) && !startListLoading()) {
      event.preventDefault();
      return;
    }
    if (!form.matches(listLoadingTriggerSelector)
        && form.dataset.pageLoading !== "false"
        && submitsCurrentPage(form)) {
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
    const projectSwitch = event.target.closest?.(projectSwitchSelector);
    if (projectSwitch?.value) {
      window.location.assign(projectSwitch.value);
      return;
    }
    const select = event.target.closest?.("[data-list-loading-page-size]");
    select?.form?.requestSubmit();
  });

  window.addEventListener("pageshow", () => {
    resetSubmitLoading();
    resetListLoading();
    resetPageLoading();
  });
})();
