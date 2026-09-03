(() => {
  const guide = document.getElementById('design-guide');
  if (!guide) return;

  const storageKey = 'we-adk-builder-design-guide-draft';
  const root = document.documentElement;
  const editToggle = document.getElementById('guide-edit-toggle');
  const tokenEditor = document.getElementById('token-editor');
  const tokenEditorForm = document.getElementById('token-editor-form');
  const tokenEditorTitle = document.getElementById('token-editor-title');
  const tokenEditorPath = document.getElementById('token-editor-path');
  const tokenEditorValue = document.getElementById('token-editor-value');
  const tokenEditorColor = document.getElementById('token-editor-color');
  const changeList = document.getElementById('guide-change-list');
  const changeCount = document.getElementById('guide-change-count');
  const saveButton = document.getElementById('save-guide-draft');
  const undoButton = document.getElementById('undo-guide-change');
  const resetButton = document.getElementById('reset-guide-changes');
  const copyButton = document.getElementById('copy-guide-prompt');
  const toast = document.getElementById('guide-toast');
  const baseValues = new Map();
  const changes = new Map();
  const history = [];
  let editingTarget = null;
  let toastTimer = null;

  function readToken(token) {
    return getComputedStyle(root).getPropertyValue(token).trim();
  }

  function setPreviewStyles() {
    document.querySelectorAll('[data-preview]').forEach(element => {
      element.style.setProperty('--swatch', element.dataset.preview);
      if (element.dataset.previewText) element.style.setProperty('--swatch-text', element.dataset.previewText);
    });
    document.querySelectorAll('[data-token-preview]').forEach(element => {
      element.style.setProperty('--token-preview', element.dataset.tokenPreview);
      if (element.dataset.tokenText) element.style.setProperty('--token-text', element.dataset.tokenText);
    });
    document.querySelectorAll('[data-size]').forEach(element => element.style.setProperty('--sample-size', element.dataset.size));
    document.querySelectorAll('[data-shadow]').forEach(element => element.style.boxShadow = element.dataset.shadow);
    document.querySelectorAll('[data-font-size]').forEach(element => element.style.fontSize = element.dataset.fontSize);
    document.querySelectorAll('.radius-sample[data-token]').forEach(element => element.style.borderRadius = `var(${element.dataset.token})`);
  }

  function tokenTargets() {
    return [...document.querySelectorAll('[data-token]')];
  }

  function rememberBaseValues() {
    tokenTargets().forEach(element => {
      const token = element.dataset.token;
      if (!baseValues.has(token)) baseValues.set(token, readToken(token));
    });
  }

  function updateDisplayedValues() {
    document.querySelectorAll('.token-swatch[data-token]').forEach(element => {
      const value = readToken(element.dataset.token);
      element.querySelector('span').textContent = value;
      element.title = `${element.dataset.token} · ${value}`;
    });
    document.querySelectorAll('.shell-token[data-token] code').forEach(code => {
      const token = code.parentElement.dataset.token;
      code.textContent = readToken(token);
    });
    document.querySelectorAll('.radius-sample[data-token] code').forEach(code => {
      const token = code.parentElement.dataset.token;
      code.textContent = `${token} · ${readToken(token)}`;
    });
  }

  function showToast(message) {
    toast.textContent = message;
    toast.hidden = false;
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => { toast.hidden = true; }, 2400);
  }

  function renderChanges() {
    const entries = [...changes.entries()];
    changeCount.textContent = `토큰 ${entries.length}개 변경`;
    const disabled = entries.length === 0;
    saveButton.disabled = disabled;
    undoButton.disabled = history.length === 0;
    resetButton.disabled = disabled;
    copyButton.disabled = disabled;
    changeList.innerHTML = '';
    if (disabled) {
      const empty = document.createElement('li');
      empty.className = 'guide-change-empty';
      empty.textContent = '아직 변경한 토큰이 없습니다. 토큰 편집을 켜고 색상·셸·라운딩 견본을 선택하면 변경안을 비교할 수 있습니다.';
      changeList.append(empty);
      return;
    }
    entries.forEach(([token, change]) => {
      const item = document.createElement('li');
      item.className = 'guide-change-item';
      const name = document.createElement('strong');
      const value = document.createElement('span');
      name.textContent = token;
      value.textContent = `${change.from} → ${change.to}`;
      item.append(name, value);
      changeList.append(item);
    });
  }

  function applyToken(token, value, options = {}) {
    const previous = readToken(token);
    if (!options.skipHistory) history.push({ token, value: previous });
    root.style.setProperty(token, value);
    const base = baseValues.get(token) ?? previous;
    if (value === base) changes.delete(token);
    else changes.set(token, { from: base, to: value });
    updateDisplayedValues();
    renderChanges();
  }

  function toHex(value) {
    const probe = document.createElement('span');
    probe.style.color = value;
    document.body.append(probe);
    const normalized = getComputedStyle(probe).color;
    probe.remove();
    const match = normalized.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
    if (!match) return null;
    return `#${[match[1], match[2], match[3]].map(part => Number(part).toString(16).padStart(2, '0')).join('')}`;
  }

  function openTokenEditor(target) {
    if (editToggle.getAttribute('aria-pressed') !== 'true') return;
    editingTarget = target;
    const token = target.dataset.token;
    const value = readToken(token);
    tokenEditorTitle.textContent = target.dataset.tokenLabel || '디자인 토큰 변경';
    tokenEditorPath.textContent = token;
    tokenEditorValue.value = value;
    const hex = toHex(value);
    tokenEditorColor.hidden = !hex;
    if (hex) tokenEditorColor.value = hex;
    tokenEditor.showModal();
    tokenEditorValue.focus();
    tokenEditorValue.select();
  }

  function activateTab(tab) {
    document.querySelectorAll('[data-guide-tab]').forEach(button => {
      const active = button.dataset.guideTab === tab;
      button.setAttribute('aria-selected', String(active));
      button.tabIndex = active ? 0 : -1;
    });
    document.querySelectorAll('.guide-section[role="tabpanel"]').forEach(panel => {
      panel.hidden = panel.id !== `panel-${tab}`;
    });
    sessionStorage.setItem('we-adk-builder-design-guide-tab', tab);
  }

  document.querySelectorAll('[data-guide-tab]').forEach(button => {
    button.addEventListener('click', () => activateTab(button.dataset.guideTab));
    button.addEventListener('keydown', event => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const tabs = [...document.querySelectorAll('[data-guide-tab]')];
      const current = tabs.indexOf(button);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (current + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
      tabs[next].focus();
      activateTab(tabs[next].dataset.guideTab);
    });
  });

  editToggle.addEventListener('click', () => {
    const active = editToggle.getAttribute('aria-pressed') !== 'true';
    editToggle.setAttribute('aria-pressed', String(active));
    guide.classList.toggle('is-editing', active);
    if (active) showToast('편집할 토큰 견본을 선택해 주세요.');
  });

  tokenTargets().forEach(target => target.addEventListener('click', () => openTokenEditor(target)));
  tokenEditorColor.addEventListener('input', () => { tokenEditorValue.value = tokenEditorColor.value; });
  tokenEditorValue.addEventListener('input', () => {
    const hex = toHex(tokenEditorValue.value);
    if (hex) tokenEditorColor.value = hex;
  });
  tokenEditorForm.addEventListener('submit', event => {
    event.preventDefault();
    const submitter = event.submitter?.value;
    if (submitter === 'apply' && editingTarget && tokenEditorValue.value.trim()) {
      applyToken(editingTarget.dataset.token, tokenEditorValue.value.trim());
      showToast(`${editingTarget.dataset.tokenLabel || editingTarget.dataset.token} 변경안을 적용했습니다.`);
    }
    tokenEditor.close();
    editingTarget = null;
  });

  undoButton.addEventListener('click', () => {
    const last = history.pop();
    if (!last) return;
    applyToken(last.token, last.value, { skipHistory: true });
    showToast('마지막 토큰 변경을 취소했습니다.');
  });
  resetButton.addEventListener('click', () => {
    changes.forEach((_change, token) => root.style.setProperty(token, baseValues.get(token)));
    changes.clear();
    history.length = 0;
    updateDisplayedValues();
    renderChanges();
    showToast('모든 변경안을 초기화했습니다.');
  });
  saveButton.addEventListener('click', () => {
    const draft = Object.fromEntries([...changes].map(([token, change]) => [token, change.to]));
    localStorage.setItem(storageKey, JSON.stringify(draft));
    showToast('디자인가이드 초안을 이 브라우저에 저장했습니다.');
  });
  copyButton.addEventListener('click', async () => {
    const lines = ['Builder 디자인 토큰 변경안을 공통 CSS 정본에 반영해 주세요.', '', ...[...changes].map(([token, change]) => `- ${token}: ${change.from} → ${change.to}`), '', '변경 뒤 전체 제품 목업을 데스크톱과 375px에서 확인하고 check_mockups.py를 실행해 주세요.'];
    try {
      await navigator.clipboard.writeText(lines.join('\n'));
      showToast('AI 반영 지시문을 복사했습니다.');
    } catch (_error) {
      showToast('복사하지 못했습니다. 브라우저의 클립보드 권한을 확인해 주세요.');
    }
  });

  document.querySelectorAll('[data-pattern-width]').forEach(button => {
    button.addEventListener('click', () => {
      const mobile = button.dataset.patternWidth === 'mobile';
      document.getElementById('pattern-list-preview').classList.toggle('is-mobile', mobile);
      document.querySelectorAll('[data-pattern-width]').forEach(item => {
        const active = item === button;
        item.setAttribute('aria-pressed', String(active));
        item.classList.toggle('button--active', active);
      });
    });
  });

  document.getElementById('open-guide-layer-dialog').addEventListener('click', () => {
    const dialog = document.getElementById('guide-layer-dialog');
    dialog.showModal();
    dialog.querySelector('input')?.focus();
  });
  document.getElementById('open-guide-dialog').addEventListener('click', () => document.getElementById('guide-example-dialog').showModal());
  document.getElementById('show-guide-toast').addEventListener('click', () => showToast('정리 내용을 저장했습니다.'));
  document.querySelectorAll('a[href="#"]').forEach(link => link.addEventListener('click', event => event.preventDefault()));

  setPreviewStyles();
  rememberBaseValues();
  let savedDraft = {};
  try {
    savedDraft = JSON.parse(localStorage.getItem(storageKey) || '{}');
  } catch (_error) {
    localStorage.removeItem(storageKey);
  }
  Object.entries(savedDraft).forEach(([token, value]) => {
    if (baseValues.has(token) && typeof value === 'string') applyToken(token, value, { skipHistory: true });
  });
  updateDisplayedValues();
  renderChanges();
  const savedTab = sessionStorage.getItem('we-adk-builder-design-guide-tab');
  if (savedTab && document.querySelector(`[data-guide-tab="${savedTab}"]`)) activateTab(savedTab);
})();
