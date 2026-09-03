(() => {
  const workbench = document.querySelector("[data-menu-tree-workbench]");
  if (!workbench) return;

  const selectionCache = new Map();
  const basePath = window.location.pathname.replace(/\/$/, "");
  let activeRequest;
  let activeEditRequest;
  let editTrigger;
  let selectionSequence = 0;

  function setBusy(busy) {
    workbench.toggleAttribute("aria-busy", busy);
    workbench.querySelector(".ia-detail-loading")?.setAttribute("aria-hidden", String(!busy));
  }

  function menuLink(event) {
    const link = event.target.closest?.("a[data-menu-node-key]");
    if (!link || event.button !== 0 || event.metaKey || event.ctrlKey
        || event.shiftKey || event.altKey || link.target) return null;
    return link;
  }

  function setBranchExpanded(button, expanded) {
    const children = button.closest("li")?.querySelector(":scope > ul");
    if (!children) return;
    children.hidden = !expanded;
    button.setAttribute("aria-expanded", String(expanded));
    button.setAttribute("aria-label", expanded ? "하위 메뉴 접기" : "하위 메뉴 펼치기");
    button.title = expanded ? "하위 메뉴 접기" : "하위 메뉴 펼치기";
    button.querySelector("[aria-hidden]").textContent = expanded ? "−" : "+";
  }

  function toggleBranch(button) {
    setBranchExpanded(button, button.getAttribute("aria-expanded") !== "true");
  }

  function selectTreeNode(nodeKey) {
    const links = Array.from(document.querySelectorAll("a[data-menu-node-key]"));
    links.forEach(link => {
      link.classList.remove("is-current", "is-path");
      link.removeAttribute("aria-current");
    });

    const selected = links.find(link => link.dataset.menuNodeKey === nodeKey);
    if (!selected) throw new Error("선택한 메뉴 트리 노드를 찾지 못했습니다.");
    selected.classList.add("is-current");
    selected.setAttribute("aria-current", "page");

    let item = selected.closest("li");
    while (item) {
      const parent = item.parentElement?.closest("li");
      if (!parent) break;
      parent.querySelector(":scope > .ia-tree-row-wrap > a[data-menu-node-key]")?.classList.add("is-path");
      const toggle = parent.querySelector(":scope > .ia-tree-row-wrap > [data-tree-toggle]");
      if (toggle) setBranchExpanded(toggle, true);
      item = parent;
    }
  }

  function text(selector, value) {
    const element = document.querySelector(selector);
    if (element) element.textContent = value ?? "";
  }

  function hidden(selector, value) {
    const element = document.querySelector(selector);
    if (element) element.hidden = value;
  }

  function editDialog() {
    return document.querySelector("[data-menu-dialog]");
  }

  function setEditLoading(dialog, loading) {
    dialog.toggleAttribute("aria-busy", loading);
    hidden("[data-menu-edit-loading]", !loading);
  }

  function showEditError(dialog, message) {
    const error = dialog.querySelector("[data-menu-edit-error]");
    if (!error) return;
    error.textContent = message;
    error.hidden = false;
  }

  async function openEditDialog(trigger) {
    const dialog = editDialog();
    if (!dialog || typeof dialog.showModal !== "function") {
      window.location.assign(trigger.href);
      return;
    }

    editTrigger = trigger;
    text("#menu-edit-dialog-title", trigger.dataset.dialogTitle || "메뉴 정보");
    text("#menu-edit-dialog-description", trigger.dataset.dialogDescription || "메뉴 정보를 입력합니다.");
    activeEditRequest?.abort();
    const request = new AbortController();
    activeEditRequest = request;
    dialog.querySelector("[data-menu-edit-content]")?.replaceChildren();
    const error = dialog.querySelector("[data-menu-edit-error]");
    if (error) {
      error.hidden = true;
      error.textContent = "";
    }
    if (!dialog.open) dialog.showModal();
    setEditLoading(dialog, true);

    try {
      const response = await fetch(trigger.href, {
        headers: { "Accept": "text/html" },
        signal: request.signal
      });
      if (!response.ok) throw new Error(`메뉴 정보 조회 실패: ${response.status}`);
      const nextDocument = new DOMParser().parseFromString(await response.text(), "text/html");
      const editor = nextDocument.querySelector("form.ia-row-editor");
      const content = dialog.querySelector("[data-menu-edit-content]");
      if (!editor || !content) throw new Error("메뉴 입력 폼을 찾지 못했습니다.");
      dialog.classList.toggle("ia-menu-edit-dialog--create", editor.hasAttribute("data-menu-create-form"));
      editor.dataset.menuDialogForm = "";
      const cancel = editor.querySelector(".ia-editor-actions a");
      if (cancel) cancel.dataset.menuDialogClose = "";
      content.replaceChildren(document.importNode(editor, true));
      const importedForm = content.querySelector("form[data-menu-dialog-form]");
      importedForm?.querySelector("[autofocus], input:not([type='hidden']), select")?.focus();
    } catch (error) {
      if (error.name === "AbortError") return;
      showEditError(dialog, "메뉴 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
    } finally {
      if (activeEditRequest === request) {
        activeEditRequest = undefined;
        setEditLoading(dialog, false);
      }
    }
  }

  async function submitEditForm(form) {
    const dialog = editDialog();
    if (!dialog) return;
    const submit = form.querySelector("button[type='submit']");
    form.setAttribute("aria-busy", "true");
    if (submit) submit.disabled = true;
    const error = dialog.querySelector("[data-menu-edit-error]");
    if (error) error.hidden = true;

    try {
      const response = await fetch(form.action, {
        method: "POST",
        body: new FormData(form),
        headers: { "Accept": "text/html" }
      });
      if (!response.ok) throw new Error(`메뉴 저장 실패: ${response.status}`);
      const nextDocument = new DOMParser().parseFromString(await response.text(), "text/html");
      const rejected = nextDocument.querySelector("[data-menu-tree-feedback] .source-error");
      if (rejected) {
        showEditError(dialog, rejected.textContent);
        return;
      }
      replaceChangedWorkbench(nextDocument);
      window.history.replaceState({ menuTreeSelection: true }, "", response.url);
      dialog.close();
    } catch (error) {
      showEditError(dialog, "메뉴 정보를 저장하지 못했습니다. 입력 내용을 유지한 채 다시 시도해 주세요.");
    } finally {
      form.removeAttribute("aria-busy");
      if (submit) submit.disabled = false;
    }
  }

  function applySelection(selection) {
    document.querySelector("[data-menu-tree-feedback]")?.replaceChildren();
    document.querySelectorAll("[data-menu-node-key-input]")
      .forEach(input => { input.value = selection.nodeKey; });
    document.querySelectorAll("[data-menu-version-input]")
      .forEach(input => { input.value = selection.version; });
    const moveUp = document.querySelector("[data-menu-move-up]");
    const moveDown = document.querySelector("[data-menu-move-down]");
    if (moveUp) moveUp.disabled = !selection.canMoveUp;
    if (moveDown) moveDown.disabled = !selection.canMoveDown;

    const deleteForm = document.querySelector("[data-menu-delete-form]");
    if (deleteForm) {
      deleteForm.hidden = !selection.rowId;
      deleteForm.action = selection.rowId
        ? `${basePath}/rows/${encodeURIComponent(selection.rowId)}/delete` : "#";
    }
    const edit = document.querySelector("[data-menu-edit]");
    if (edit) {
      edit.hidden = !selection.rowId;
      edit.href = selection.rowId
        ? `${basePath}/rows/${encodeURIComponent(selection.rowId)}/edit` : "#";
    }

    text("[data-selection-label]", selection.label);
    text("[data-selection-parent]", selection.parentLabel || "최상위");
    text("[data-selection-depth]", `Depth ${selection.depth}`);
    text("[data-selection-menu-type]", selection.menuType);
    text("[data-selection-application-target]", selection.applicationTarget);
    hidden("[data-selection-group-note]", Boolean(selection.rowId));

    text("[data-selection-standard-id]", selection.standardScreenId || "—");
    text("[data-selection-screen-type]", selection.screenType || "—");
    text("[data-selection-screen-summary]", selection.screenSummary || "등록된 화면 요약이 없습니다.");
    hidden("[data-selection-type-review]", !selection.screenTypeNeedsReview);
  }

  function selectionEndpoint(url) {
    const endpoint = new URL(url, window.location.href);
    endpoint.pathname = endpoint.pathname.replace(/\/$/, "") + "/selection";
    endpoint.searchParams.delete("rowId");
    return endpoint;
  }

  async function requestSelection(targetUrl, endpoint, sequence) {
    const request = new AbortController();
    activeRequest = request;
    try {
      const response = await fetch(endpoint, {
        headers: { "Accept": "application/json" },
        signal: request.signal
      });
      if (!response.ok) throw new Error(`메뉴 상세 조회 실패: ${response.status}`);
      const selection = await response.json();
      if (sequence !== selectionSequence) return;
      selectionCache.set(selection.nodeKey, selection);
      applySelection(selection);
    } catch (error) {
      if (error.name === "AbortError") return;
      window.location.assign(targetUrl);
    } finally {
      if (sequence === selectionSequence) {
        activeRequest = undefined;
        setBusy(false);
      }
    }
  }

  function loadSelection(url, historyMode) {
    const target = new URL(url, window.location.href);
    const nodeKey = target.searchParams.get("nodeKey");
    if (!nodeKey) {
      window.location.assign(target.href);
      return;
    }

    selectionSequence += 1;
    const sequence = selectionSequence;
    activeRequest?.abort();
    activeRequest = undefined;
    selectTreeNode(nodeKey);
    if (historyMode === "push") window.history.pushState({ menuTreeSelection: true }, "", target.href);

    const cached = selectionCache.get(nodeKey);
    if (cached) {
      applySelection(cached);
      setBusy(false);
      return;
    }

    setBusy(true);
    requestSelection(target.href, selectionEndpoint(target), sequence);
  }

  function replaceTree(nextDocument) {
    const tree = document.querySelector("[data-menu-tree-nav]");
    const nextTree = nextDocument.querySelector("[data-menu-tree-nav]");
    if (!tree || !nextTree) throw new Error("변경된 메뉴 트리를 찾지 못했습니다.");

    const scrollTop = tree.scrollTop;
    const collapsed = new Set(Array.from(tree.querySelectorAll("[data-tree-toggle][aria-expanded='false']"))
      .map(button => button.closest("li")?.querySelector(":scope > .ia-tree-row-wrap a[data-menu-node-key]")?.dataset.menuNodeKey)
      .filter(Boolean));
    tree.replaceChildren(...Array.from(nextTree.childNodes).map(node => document.importNode(node, true)));
    collapsed.forEach(nodeKey => {
      const link = Array.from(tree.querySelectorAll("a[data-menu-node-key]"))
        .find(item => item.dataset.menuNodeKey === nodeKey);
      const button = link?.closest(".ia-tree-row-wrap")?.querySelector("[data-tree-toggle]");
      if (button) setBranchExpanded(button, false);
    });
    tree.scrollTop = scrollTop;
  }

  function replaceChangedWorkbench(nextDocument) {
    const selectors = ["[data-menu-tree-feedback]", "[data-menu-tree-meta]", "[data-menu-tree-tools]",
      "[data-menu-tree-detail]", "[data-menu-unlinked]"];
    replaceTree(nextDocument);
    selectors.forEach(selector => {
      const current = document.querySelector(selector);
      const next = nextDocument.querySelector(selector);
      if (!current || !next) throw new Error("변경된 메뉴 응답이 올바르지 않습니다.");
      current.replaceWith(document.importNode(next, true));
    });
    selectionCache.clear();
    if (workbench.hasAttribute("aria-busy")) setBusy(true);
  }

  function moveTreeNode(nodeKey, direction) {
    const selected = Array.from(document.querySelectorAll("a[data-menu-node-key]"))
      .find(link => link.dataset.menuNodeKey === nodeKey);
    const item = selected?.closest("li");
    const sibling = direction === "up" ? item?.previousElementSibling : item?.nextElementSibling;
    if (!item || !sibling || item.parentElement !== sibling.parentElement) {
      throw new Error("이동할 메뉴 위치를 찾지 못했습니다.");
    }
    if (direction === "up") item.parentElement.insertBefore(item, sibling);
    else item.parentElement.insertBefore(sibling, item);
  }

  function applyMoveResult(result) {
    moveTreeNode(result.nodeKey, result.direction);
    document.querySelectorAll("[data-menu-version-input]")
      .forEach(input => { input.value = result.version; });
    const moveUp = document.querySelector("[data-menu-move-up]");
    const moveDown = document.querySelector("[data-menu-move-down]");
    if (moveUp) moveUp.disabled = !result.canMoveUp;
    if (moveDown) moveDown.disabled = !result.canMoveDown;
    selectionCache.clear();
    const feedback = document.querySelector("[data-menu-tree-feedback]");
    if (feedback) {
      const message = document.createElement("p");
      message.className = "source-success";
      message.textContent = "같은 단계에서 메뉴 순서를 변경했습니다.";
      feedback.replaceChildren(message);
    }
  }

  async function moveMenu(form) {
    selectionSequence += 1;
    activeRequest?.abort();
    const request = new AbortController();
    activeRequest = request;
    setBusy(true);
    try {
      const response = await fetch(form.action, {
        method: "POST",
        body: new FormData(form),
        headers: { "Accept": "application/json" },
        signal: request.signal
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || `메뉴 이동 실패: ${response.status}`);
      applyMoveResult(result);
    } catch (error) {
      if (error.name === "AbortError") return;
      window.location.reload();
    } finally {
      if (activeRequest === request) {
        activeRequest = undefined;
        setBusy(false);
      }
    }
  }

  document.addEventListener("click", event => {
    const close = event.target.closest?.("[data-menu-dialog-close]");
    if (close) {
      event.preventDefault();
      editDialog()?.close();
      return;
    }

    const edit = event.target.closest?.("[data-menu-dialog-open]");
    if (edit) {
      event.preventDefault();
      openEditDialog(edit);
      return;
    }

    const toggle = event.target.closest?.("button[data-tree-toggle]");
    if (toggle) {
      toggleBranch(toggle);
      return;
    }

    const link = menuLink(event);
    if (!link) return;
    event.preventDefault();
    if (link.getAttribute("aria-current") === "page") return;
    loadSelection(link.href, "push");
  }, true);

  document.addEventListener("submit", event => {
    const form = event.target.closest?.("form[data-menu-dialog-form]");
    if (!form) return;
    event.preventDefault();
    event.stopPropagation();
    submitEditForm(form);
  }, true);

  document.addEventListener("submit", event => {
    const form = event.target.closest?.("form[data-menu-move-form]");
    if (!form) return;
    event.preventDefault();
    moveMenu(form);
  }, true);

  window.addEventListener("popstate", () => loadSelection(window.location.href, "none"));

  const dialog = editDialog();
  dialog?.addEventListener("click", event => {
    if (event.target === dialog) dialog.close();
  });
  dialog?.addEventListener("close", () => {
    activeEditRequest?.abort();
    activeEditRequest = undefined;
    editTrigger?.focus();
    editTrigger = undefined;
    dialog.classList.remove("ia-menu-edit-dialog--create");
  });
})();
