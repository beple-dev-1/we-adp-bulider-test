(function () {
  const panel = document.querySelector('.wm-ai-chat--window');
  const log = document.getElementById('frd-chat-window-log');
  const form = document.getElementById('frd-chat-window-form');
  const message = document.getElementById('frd-chat-window-message');
  const send = document.getElementById('frd-chat-window-send');
  const referenceInput = document.getElementById('frd-chat-window-reference-input');
  const reference = document.getElementById('frd-chat-window-reference');
  const referenceName = document.getElementById('frd-chat-window-reference-name');
  const referenceRemove = document.getElementById('frd-chat-window-reference-remove');
  const suggestionToggle = document.getElementById('frd-chat-window-suggestion-toggle');
  const suggestionMenu = document.getElementById('frd-chat-window-suggestion-menu');
  const selectionPanel = document.getElementById('frd-chat-window-selection');
  const selectionLabel = document.getElementById('frd-chat-window-selection-label');
  const selectionPath = document.getElementById('frd-chat-window-selection-path');
  if (!panel || !log || !form || !message || !send) return;
  let renderSignature = '';
  let activeRequestId = null;
  let selectedRegion = null;
  let referenceImage = null;
  let dragDepth = 0;

  const acceptedImageTypes = new Set(['image/png', 'image/jpeg', 'image/webp']);
  const maxReferenceImageBytes = 10 * 1024 * 1024;
  const safeFailure = (value, fallback) => {
    const text = String(value || '').trim();
    const technical = /apiStatus|terminalReason|exitCode|API Error|Exception|\bat [\w$.]+\(|[A-Za-z]:\\|^\s*[\[{]/i;
    return !text || text.length > 320 || technical.test(text) ? fallback : text;
  };
  const closeSuggestionMenu = restoreFocus => {
    if (!suggestionMenu || suggestionMenu.hidden) return;
    suggestionMenu.hidden = true;
    suggestionToggle?.setAttribute('aria-expanded', 'false');
    if (restoreFocus) suggestionToggle?.focus();
  };

  const renderSelection = () => {
    selectionPanel.hidden = !selectedRegion;
    selectionLabel.textContent = selectedRegion?.label || '';
    selectionPath.textContent = selectedRegion?.selector || '';
  };

  const articleOf = (role, content, state) => {
    const article = document.createElement('article');
    article.className = 'wm-ai-chat-message wm-ai-chat-message--' + (role === 'USER' ? 'user' : 'ai');
    const failed = state === 'FAILED';
    const cancelled = failed && content?.startsWith('사용자가');
    if (failed) article.classList.add('wm-ai-chat-message--error');
    if (cancelled) article.classList.add('wm-ai-chat-message--cancelled');
    const author = document.createElement('strong');
    author.textContent = role === 'USER' ? '나'
      : (failed ? (cancelled ? '작업을 중단했습니다' : '요청을 완료하지 못했습니다') : 'Claude Code');
    const body = document.createElement('p');
    body.textContent = content.replace(/\\r\\n|\\n|\\r/g, '\n');
    article.append(author, body);
    if (failed && !cancelled) {
      const guide = document.createElement('small');
      guide.className = 'wm-ai-chat-message__guide';
      guide.textContent = '입력한 요청은 대화에 남아 있습니다.';
      article.append(guide);
    }
    return article;
  };

  const setBusy = busy => {
    message.disabled = busy;
    if (referenceInput) referenceInput.disabled = busy;
    if (referenceRemove) referenceRemove.disabled = busy;
    if (suggestionToggle) suggestionToggle.disabled = busy;
    send.disabled = busy;
    send.textContent = busy ? '확인 중' : '전송';
    if (busy) {
      closeSuggestionMenu(false);
      showDropTarget(false);
    }
  };

  const renderReferenceImage = () => {
    if (reference) reference.hidden = !referenceImage;
    if (referenceName) referenceName.textContent = referenceImage?.name || '';
    if (referenceRemove) referenceRemove.hidden = !referenceImage;
  };
  referenceRemove?.addEventListener('click', () => {
    referenceImage = null;
    if (referenceInput) referenceInput.value = '';
    renderReferenceImage();
    message.focus();
  });

  const hasDraggedFiles = transfer => {
    const types = Array.from(transfer?.types || []);
    const items = Array.from(transfer?.items || []);
    return types.includes('Files') || items.some(item => item.kind === 'file');
  };
  const droppedImage = files => Array.from(files || [])[0];
  const acceptReferenceImage = file => {
    const acceptedExtension = /\.(png|jpe?g|webp)$/i.test(file?.name || '');
    if (!file || (!acceptedImageTypes.has(file.type) && !acceptedExtension)) {
      throw new Error('참고 이미지는 PNG, JPEG, WebP 형식만 사용할 수 있습니다.');
    }
    if (file.size > maxReferenceImageBytes) {
      throw new Error('참고 이미지는 10MB 이하로 올려 주세요.');
    }
    referenceImage = file;
    renderReferenceImage();
  };
  referenceInput?.addEventListener('change', () => {
    try {
      acceptReferenceImage(referenceInput.files[0]);
    } catch (error) {
      referenceInput.value = '';
      log.append(articleOf('AI', error.message, 'FAILED'));
      log.scrollTop = log.scrollHeight;
    }
  });
  const showDropTarget = shown => {
    form.classList.toggle('is-dragover', shown && !send.disabled);
  };
  const isChatFileDrag = event => panel.contains(event.target) && hasDraggedFiles(event.dataTransfer);
  window.addEventListener('dragenter', event => {
    if (!isChatFileDrag(event)) return;
    event.preventDefault();
    dragDepth += 1;
    showDropTarget(true);
  }, true);
  window.addEventListener('dragover', event => {
    if (!isChatFileDrag(event)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = send.disabled ? 'none' : 'copy';
  }, true);
  window.addEventListener('dragleave', event => {
    if (!isChatFileDrag(event)) return;
    dragDepth = Math.max(0, dragDepth - 1);
    if (!dragDepth) showDropTarget(false);
  }, true);
  window.addEventListener('drop', event => {
    if (!isChatFileDrag(event)) return;
    event.preventDefault();
    dragDepth = 0;
    showDropTarget(false);
    if (send.disabled) return;
    try {
      acceptReferenceImage(droppedImage(event.dataTransfer.files));
    } catch (error) {
      log.append(articleOf('AI', error.message, 'FAILED'));
      log.scrollTop = log.scrollHeight;
    }
  }, true);

  const render = status => {
    const signature = JSON.stringify({ messages: status.messages, active: status.active });
    if (signature === renderSignature) return;
    renderSignature = signature;
    log.replaceChildren();
    if (!status.messages.length) {
      log.append(articleOf('AI', '현재 화면을 질문하거나 수정·신규 화면을 요청해 주세요.', 'DONE'));
    }
    status.messages.forEach(item => {
      const content = item.state === 'RUNNING' ? '현재 화면과 요청 내용을 확인하고 있습니다.'
        : (item.state === 'FAILED'
          ? safeFailure(item.failure, '화면 요청을 완료하지 못했습니다. 잠시 후 다시 요청해 주세요.')
          : item.content);
      const article = articleOf(item.role, content, item.state);
      if (item.state === 'RUNNING' && status.active?.screenRowId === panel.dataset.screenRowId) {
        const steps = document.createElement('ol');
        steps.className = 'wm-ai-chat-progress';
        (status.active.progress || []).forEach(step => {
          const row = document.createElement('li');
          row.textContent = step.text;
          steps.append(row);
        });
        article.append(steps);
      }
      log.append(article);
    });
    if (status.active && status.active.screenRowId !== panel.dataset.screenRowId) {
      const notice = document.createElement('p');
      notice.className = 'wm-ai-chat__notice';
      notice.textContent = status.active.screenName + ' 화면 요청을 처리하고 있습니다. 완료된 뒤 요청할 수 있습니다.';
      log.append(notice);
    }
    setBusy(Boolean(status.active));
    log.scrollTop = log.scrollHeight;
  };

  const refresh = async () => {
    try {
      const response = await fetch(panel.dataset.statusUrl, { headers: { 'Accept': 'application/json' } });
      if (response.ok) {
        const status = await response.json();
        const completedRequestId = activeRequestId && !status.active ? activeRequestId : null;
        activeRequestId = status.active?.id || null;
        render(status);
        if (completedRequestId && window.opener && !window.opener.closed) {
          window.opener.postMessage({
            type: 'frd-screen-chat-completed',
            screenRowId: panel.dataset.screenRowId,
            requestId: completedRequestId,
            screenCount: status.screenCount
          }, window.location.origin);
        }
      }
    } catch (error) {
      // 다음 자동 확인에서 다시 시도한다.
    }
  };

  form.addEventListener('submit', async event => {
    event.preventDefault();
    const request = message.value.trim();
    if ((!request && !referenceImage) || send.disabled) return;
    const body = new FormData(form);
    body.append('message', request);
    if (referenceImage) body.append('referenceImage', referenceImage, referenceImage.name);
    if (selectedRegion) body.append('selectedRegion', JSON.stringify(selectedRegion));
    setBusy(true);
    try {
      const response = await fetch(panel.dataset.sendUrl, { method: 'POST', body });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail || problem?.message || '화면 요청을 시작하지 못했습니다.');
      }
      const started = await response.json();
      activeRequestId = started.id;
      message.value = '';
      referenceImage = null;
      if (referenceInput) referenceInput.value = '';
      renderReferenceImage();
      await refresh();
    } catch (error) {
      log.append(articleOf('AI', error.message || '화면 요청을 시작하지 못했습니다.', 'FAILED'));
      setBusy(false);
    }
  });

  message.addEventListener('keydown', event => {
    if (event.key !== 'Enter' || event.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    if (event.altKey) {
      const start = message.selectionStart;
      message.setRangeText('\n', start, message.selectionEnd, 'end');
      message.dispatchEvent(new Event('input', { bubbles: true }));
      return;
    }
    if (!send.disabled) form.requestSubmit();
  });
  suggestionToggle?.addEventListener('click', () => {
    if (suggestionToggle.disabled || !suggestionMenu) return;
    const opening = suggestionMenu.hidden;
    suggestionMenu.hidden = !opening;
    suggestionToggle.setAttribute('aria-expanded', String(opening));
    if (opening) suggestionMenu.querySelector('[role="menuitem"]')?.focus();
  });
  suggestionMenu?.querySelectorAll('[data-screen-chat-suggestion]').forEach(button => {
    button.addEventListener('click', () => {
      const suggestion = button.dataset.screenChatSuggestion?.trim();
      closeSuggestionMenu(false);
      if (!suggestion || send.disabled) return;
      message.value = suggestion;
      form.requestSubmit();
    });
  });
  document.addEventListener('click', event => {
    if (!suggestionMenu?.hidden && !event.target.closest('.wm-ai-chat__suggestion-control')) {
      closeSuggestionMenu(false);
    }
  });
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || suggestionMenu?.hidden) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    closeSuggestionMenu(true);
  }, true);

  let cancelPending = false;
  const cancelActiveChat = async () => {
    if (!activeRequestId || cancelPending) return;
    cancelPending = true;
    setBusy(true);
    try {
      const csrf = form.querySelector('input[name="_csrf"]');
      const headers = {};
      if (csrf) headers['X-CSRF-TOKEN'] = csrf.value;
      const response = await fetch(`${panel.dataset.sendUrl}/${encodeURIComponent(activeRequestId)}/cancel`,
        { method: 'POST', headers });
      if (!response.ok) throw new Error('화면 작업을 중단하지 못했습니다. 잠시 후 다시 시도해 주세요.');
      await refresh();
    } catch (error) {
      log.append(articleOf('AI', error.message, 'FAILED'));
    } finally {
      cancelPending = false;
    }
  };
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || !activeRequestId) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    cancelActiveChat();
  }, true);

  document.getElementById('clear-frd-chat-window-selection')?.addEventListener('click', () => {
    selectedRegion = null;
    renderSelection();
    if (window.opener && !window.opener.closed) {
      window.opener.postMessage({ type: 'frd-screen-selection-cleared',
        screenRowId: panel.dataset.screenRowId }, window.location.origin);
    }
    message.focus();
  });
  window.addEventListener('message', event => {
    if (event.origin !== window.location.origin
        || event.source !== window.opener
        || event.data?.type !== 'frd-screen-selection-changed'
        || event.data.screenRowId !== panel.dataset.screenRowId) return;
    selectedRegion = event.data.selection || null;
    renderSelection();
    message.focus();
  });
  if (window.opener && !window.opener.closed) {
    window.opener.postMessage({ type: 'frd-screen-chat-ready',
      screenRowId: panel.dataset.screenRowId }, window.location.origin);
  }

  refresh().then(() => message.focus());
  let eventSource = null;
  let fallbackTimer = null;
  if ('EventSource' in window && panel.dataset.eventsUrl) {
    eventSource = new EventSource(panel.dataset.eventsUrl);
    eventSource.addEventListener('refresh', refresh);
    eventSource.addEventListener('error', refresh);
  } else {
    fallbackTimer = window.setInterval(refresh, 3000);
  }
  window.addEventListener('pagehide', () => {
    eventSource?.close();
    window.clearInterval(fallbackTimer);
  }, { once: true });
})();
