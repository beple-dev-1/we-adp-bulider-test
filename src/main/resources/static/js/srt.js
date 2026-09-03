(() => {
  const registerDialog = document.getElementById("srt-register-dialog");
  const detailDialog = document.getElementById("srt-detail-dialog");
  const deleteDialog = document.getElementById("srt-delete-dialog");
  const registerForm = document.getElementById("srt-register-form");
  const registerContent = document.querySelector("[data-srt-register-content]");
  const registerLoading = document.querySelector("[data-srt-register-loading]");
  const registerError = document.querySelector("[data-srt-register-error]");
  const registerIdleActions = document.querySelectorAll("[data-srt-register-idle]");
  const registerAnalyzingAction = document.querySelector("[data-srt-register-analyzing]");
  const requestForm = document.getElementById("srt-create-request-form");
  const requestError = document.querySelector("[data-srt-request-error]");
  const idleActions = document.querySelectorAll("[data-srt-idle-action]");
  const analyzingActions = document.querySelectorAll("[data-srt-analyzing-action]");
  let statusFailureCount = 0;
  let shouldAutoNavigate = false;
  let registrationStatusUrl = null;
  let registrationDetailUrl = null;

  function showWithoutInitialFocus(dialog) {
    dialog.showModal();
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
  }

  function clearLayerQuery() {
    const url = new URL(window.location.href);
    url.searchParams.delete("register");
    url.searchParams.delete("selected");
    window.history.replaceState(window.history.state, "", url);
  }

  function selectSource(source) {
    document.querySelectorAll("[data-srt-source]").forEach((tab) =>
      tab.setAttribute("aria-selected", String(tab.dataset.srtSource === source)));
    document.querySelectorAll("[data-srt-register-panel]").forEach((panel) => {
      panel.hidden = panel.dataset.srtRegisterPanel !== source;
    });
    const sourceInput = document.getElementById("srt-register-source");
    if (sourceInput) sourceInput.value = source;
    const title = document.getElementById("srt-direct-title");
    const content = document.getElementById("srt-direct-content");
    const flow = document.getElementById("srt-flow-task-number");
    if (title) title.required = source === "direct";
    if (content) content.required = source === "direct";
    if (flow) flow.required = source === "flow";
  }

  document.querySelectorAll("[data-srt-source]").forEach((tab) =>
    tab.addEventListener("click", () => selectSource(tab.dataset.srtSource)));
  const facetInputs = [...document.querySelectorAll("[data-srt-facet]")];
  const facetAll = document.querySelector("[data-srt-facet-all]");
  facetInputs.forEach((input) => input.addEventListener("change", () => {
    if (input.checked && facetAll) facetAll.checked = false;
    if (facetAll && !facetInputs.some((item) => item.checked)) facetAll.checked = true;
  }));
  facetAll?.addEventListener("change", () => {
    if (facetAll.checked) facetInputs.forEach((input) => { input.checked = false; });
    if (!facetAll.checked && !facetInputs.some((input) => input.checked)) facetAll.checked = true;
  });
  document.querySelectorAll("[data-srt-register-close]").forEach((button) =>
    button.addEventListener("click", () => registerDialog?.close()));
  document.querySelectorAll("[data-srt-detail-close]").forEach((button) =>
    button.addEventListener("click", () => detailDialog?.close()));
  detailDialog?.addEventListener("close", () => { shouldAutoNavigate = false; });

  const detailView = document.querySelector("[data-srt-detail-view]");
  const editView = document.querySelector("[data-srt-edit-view]");
  const detailActions = document.querySelector("[data-srt-detail-actions]");
  const editActions = document.querySelector("[data-srt-edit-actions]");
  const detailTitle = document.getElementById("srt-detail-title");
  const detailDescription = document.getElementById("srt-detail-description");
  const originalTitle = detailTitle?.textContent ?? "";
  const originalDescription = detailDescription?.textContent ?? "";

  function showMode(mode) {
    if (detailView) detailView.hidden = mode !== "detail";
    if (editView) editView.hidden = mode !== "edit";
    if (detailActions) detailActions.hidden = mode !== "detail";
    if (editActions) editActions.hidden = mode !== "edit";
    if (detailTitle) detailTitle.textContent = mode === "edit" ? "SRT 수정" : originalTitle;
    if (detailDescription) detailDescription.textContent = mode === "detail" ? originalDescription : "";
  }

  function showRegistrationLoading() {
    if (registerContent) registerContent.hidden = true;
    if (registerLoading) registerLoading.hidden = false;
    if (registerError) registerError.hidden = true;
    registerIdleActions.forEach((element) => { element.hidden = true; });
    if (registerAnalyzingAction) registerAnalyzingAction.hidden = false;
    registerDialog?.setAttribute("aria-busy", "true");
  }

  function showRegistrationError(message) {
    if (registerContent) registerContent.hidden = false;
    if (registerLoading) registerLoading.hidden = true;
    registerIdleActions.forEach((element) => { element.hidden = false; });
    if (registerAnalyzingAction) registerAnalyzingAction.hidden = true;
    if (registerError) {
      registerError.hidden = false;
      registerError.textContent = message;
    }
    registerDialog?.removeAttribute("aria-busy");
    if (registerForm) delete registerForm.dataset.submitting;
  }

  async function pollRegistrationAnalysis() {
    if (!registerDialog?.open || !registrationStatusUrl) return;
    try {
      const response = await fetch(registrationStatusUrl, { headers: { Accept: "application/json" } });
      if (!response.ok) throw new Error(`status ${response.status}`);
      const status = await response.json();
      if (!registerDialog.open) return;
      if (status.state !== "ANALYZING" && status.state !== "READY") {
        window.location.assign(status.detailUrl || registrationDetailUrl);
        return;
      }
    } catch (_) {
      showRegistrationError("SRT 분석 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
      return;
    }
    if (registerDialog.open) window.setTimeout(pollRegistrationAnalysis, 1200);
  }

  function showAnalysisState() {
    if (!detailDialog) return;
    detailDialog.dataset.analysisState = "ANALYZING";
    detailDialog.setAttribute("aria-busy", "true");
    if (requestError) requestError.hidden = true;
    idleActions.forEach((element) => { element.hidden = true; });
    analyzingActions.forEach((element) => { element.hidden = false; });
  }

  function showRequestError(message) {
    if (!detailDialog) return;
    detailDialog.dataset.analysisState = "READY";
    detailDialog.removeAttribute("aria-busy");
    idleActions.forEach((element) => { element.hidden = false; });
    analyzingActions.forEach((element) => { element.hidden = true; });
    if (requestError) {
      requestError.hidden = false;
      const description = requestError.querySelector("p");
      if (description) description.textContent = message;
    }
    if (requestForm) delete requestForm.dataset.submitting;
  }

  document.querySelector("[data-srt-edit-open]")?.addEventListener("click", () => showMode("edit"));
  document.querySelector("[data-srt-delete-open]")?.addEventListener("click", () => {
    detailDialog?.close();
    if (deleteDialog) showWithoutInitialFocus(deleteDialog);
  });
  document.querySelectorAll("[data-srt-delete-close]").forEach((button) =>
    button.addEventListener("click", () => {
      deleteDialog?.close();
      showMode("detail");
      if (detailDialog) showWithoutInitialFocus(detailDialog);
    }));
  document.querySelectorAll("[data-srt-mode-cancel]").forEach((button) =>
    button.addEventListener("click", () => showMode("detail")));

  if (registerForm) registerForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (registerForm.dataset.submitting === "true") return;
    registerForm.dataset.submitting = "true";
    showRegistrationLoading();
    try {
      const response = await fetch(registerForm.action, {
        method: "POST",
        body: new FormData(registerForm),
        credentials: "same-origin",
        headers: { Accept: "application/json" }
      });
      const status = await response.json().catch(() => null);
      if (!response.ok || !status) {
        throw new Error(status?.message || "SRT를 등록하지 못했습니다. 입력 내용을 확인해 주세요.");
      }
      registrationStatusUrl = status.statusUrl;
      registrationDetailUrl = status.detailUrl;
      if (status.state !== "ANALYZING" && status.state !== "READY") {
        if (registerDialog.open) window.location.assign(status.detailUrl);
        return;
      }
      if (registerDialog.open) window.setTimeout(pollRegistrationAnalysis, 600);
    } catch (error) {
      showRegistrationError(error instanceof Error ? error.message : "SRT를 등록하지 못했습니다. 다시 시도해 주세요.");
    }
  });

  if (requestForm) requestForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (requestForm.dataset.submitting === "true") return;
    requestForm.dataset.submitting = "true";
    shouldAutoNavigate = true;
    showAnalysisState();
    try {
      const response = await fetch(requestForm.action, {
        method: "POST",
        body: new FormData(requestForm),
        credentials: "same-origin",
        headers: { Accept: "application/json" }
      });
      const status = await response.json().catch(() => null);
      if (!response.ok || !status) {
        throw new Error(status?.message || "개발요청서 생성을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.");
      }
      if (status.state === "COMPLETE" && status.requestUrl) {
        if (shouldAutoNavigate && detailDialog.open) window.location.assign(status.requestUrl);
        return;
      }
      if (status.state !== "ANALYZING") {
        throw new Error(status.message || "개발요청서 생성을 시작하지 못했습니다. 요청 내용을 확인해 주세요.");
      }
      statusFailureCount = 0;
      if (detailDialog?.open) window.setTimeout(pollDevelopmentRequest, 600);
    } catch (error) {
      showRequestError(error instanceof Error ? error.message : "진행 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
  });

  if (registerDialog?.dataset.open === "true") {
    selectSource(registerDialog.dataset.source === "flow" ? "flow" : "direct");
    showWithoutInitialFocus(registerDialog);
  }
  if (detailDialog?.dataset.open === "true") {
    showMode("detail");
    showWithoutInitialFocus(detailDialog);
  }
  if (registerDialog?.open || detailDialog?.open) clearLayerQuery();

  async function pollDevelopmentRequest() {
    if (!detailDialog?.open || !detailDialog.dataset.statusUrl) return;
    try {
      const response = await fetch(detailDialog.dataset.statusUrl, { headers: { Accept: "application/json" } });
      if (!response.ok) throw new Error(`status ${response.status}`);
      const status = await response.json();
      if (!detailDialog.open) return;
      statusFailureCount = 0;
      if (status.state === "COMPLETE" && status.requestUrl) {
        if (shouldAutoNavigate && detailDialog.open) {
          window.location.assign(status.requestUrl);
        } else {
          window.location.replace(detailDialog.dataset.detailUrl);
        }
        return;
      }
      if (status.state === "REJECTED" || status.state === "FAILED") {
        showRequestError(status.message || "개발요청서를 생성하지 못했습니다. 요청 내용을 확인해 주세요.");
        return;
      }
      if (status.state === "READY") {
        showRequestError("진행 상태를 확인하지 못했습니다. 개발요청서 생성을 다시 시도해 주세요.");
        return;
      }
    } catch (_) {
      statusFailureCount += 1;
      if (statusFailureCount >= 4) {
        showRequestError("진행 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        return;
      }
    }
    if (detailDialog.open) window.setTimeout(pollDevelopmentRequest, 1500);
  }

  async function pollSrtAnalysisInDetail() {
    if (!detailDialog?.open || !detailDialog.dataset.srtAnalysisStatusUrl) return;
    try {
      const response = await fetch(detailDialog.dataset.srtAnalysisStatusUrl,
        { headers: { Accept: "application/json" } });
      if (!response.ok) throw new Error(`status ${response.status}`);
      const status = await response.json();
      if (!detailDialog.open) return;
      if (status.state !== "ANALYZING" && status.state !== "READY") {
        window.location.replace(status.detailUrl || detailDialog.dataset.detailUrl);
        return;
      }
    } catch (_) {
      return;
    }
    if (detailDialog.open) window.setTimeout(pollSrtAnalysisInDetail, 1200);
  }

  if (detailDialog?.dataset.analysisState === "ANALYZING") {
    showAnalysisState();
    window.setTimeout(pollDevelopmentRequest, 600);
  }
  if (detailDialog?.dataset.srtAnalysisState === "ANALYZING"
      || detailDialog?.dataset.srtAnalysisState === "READY") {
    window.setTimeout(pollSrtAnalysisInDetail, 600);
  }
})();
