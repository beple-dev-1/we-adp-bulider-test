(function () {
  const panel = document.querySelector('.wm-ai-chat--window');
  const log = document.getElementById('frd-canvas-chat-window-log');
  const form = document.getElementById('frd-canvas-chat-window-form');
  const message = document.getElementById('frd-canvas-chat-window-message');
  const send = document.getElementById('frd-canvas-chat-window-send');
  const selectionPanel = document.getElementById('frd-canvas-chat-window-selection');
  const selectionLabel = document.getElementById('frd-canvas-chat-window-selection-label');
  const selectionNames = document.getElementById('frd-canvas-chat-window-selection-names');
  const suggestionToggle = document.getElementById('frd-canvas-chat-window-suggestion-toggle');
  const suggestionMenu = document.getElementById('frd-canvas-chat-window-suggestion-menu');
  if (!panel || !log || !form || !message || !send) return;

  let renderSignature = '';
  let activeRequestId = null;
  let selectedScreens = [];
  const safeFailure = (value, fallback) => {
    const text = String(value || '').trim();
    const technical = /apiStatus|terminalReason|exitCode|API Error|Exception|\bat [\w$.]+\(|[A-Za-z]:\\|^\s*[\[{]/i;
    return !text || text.length > 320 || technical.test(text) ? fallback : text;
  };

  const renderSelection = () => {
    selectionPanel.hidden = selectedScreens.length === 0;
    selectionLabel.textContent = selectedScreens.length ? `${selectedScreens.length}개 화면 선택` : '';
    selectionNames.textContent = selectedScreens.map(screen => screen.name || screen.id).join(' · ');
    if (suggestionToggle) {
      suggestionToggle.disabled = selectedScreens.length === 0 || message.disabled;
      suggestionToggle.title = selectedScreens.length
        ? `${selectedScreens.length}개 선택 화면의 개선점을 검토합니다`
        : '검토할 화면을 먼저 선택해 주세요';
      if (!selectedScreens.length && suggestionMenu) {
        suggestionMenu.hidden = true;
        suggestionToggle.setAttribute('aria-expanded', 'false');
      }
    }
  };

  const articleOf = (role, content, state) => {
    const article = document.createElement('article');
    article.className = `wm-ai-chat-message wm-ai-chat-message--${role === 'USER' ? 'user' : 'ai'}`;
    const failed = state === 'FAILED';
    const cancelled = failed && content?.startsWith('사용자가');
    if (failed) article.classList.add('wm-ai-chat-message--error');
    if (cancelled) article.classList.add('wm-ai-chat-message--cancelled');
    const author = document.createElement('strong');
    author.textContent = role === 'USER' ? '나'
      : (failed ? (cancelled ? '작업을 중단했습니다' : '요청을 완료하지 못했습니다') : 'Claude Code');
    const body = document.createElement('p');
    body.textContent = content || '';
    article.append(author, body);
    if (failed && !cancelled) {
      const guide = document.createElement('small');
      guide.className = 'wm-ai-chat-message__guide';
      guide.textContent = '입력한 요청은 대화에 남아 있습니다.';
      article.append(guide);
    }
    return article;
  };

  const submitChatText = text => {
    if (!text || message.disabled) return;
    message.value = text;
    form.requestSubmit();
  };

  const appendInterview = (article, questions, messageId) => {
    if (!Array.isArray(questions) || !questions.length) return;
    article.classList.add('wm-ai-chat-message--interview');
    const panel = document.createElement('div');
    panel.className = 'wm-ai-interview';
    questions.forEach((question, index) => {
      const field = document.createElement('fieldset');
      field.className = 'wm-ai-interview__question';
      field.dataset.prompt = question.prompt || '';
      field.dataset.required = question.required === false ? 'false' : 'true';
      const legend = document.createElement('legend');
      legend.textContent = `${index + 1}. ${question.prompt || '확인할 내용을 입력해 주세요.'}`;
      field.append(legend);
      if (question.answerType === 'TEXT' || !Array.isArray(question.options) || !question.options.length) {
        const input = document.createElement('textarea');
        input.rows = 2;
        input.dataset.interviewAnswer = 'text';
        input.setAttribute('aria-label', question.prompt || '인터뷰 답변');
        field.append(input);
      } else {
        question.options.forEach(option => {
          const label = document.createElement('label');
          const input = document.createElement('input');
          input.type = question.answerType === 'MULTIPLE' ? 'checkbox' : 'radio';
          input.name = `canvas-interview-${messageId || 'question'}-${question.id || index}`;
          input.value = option;
          input.dataset.interviewAnswer = 'choice';
          label.append(input, document.createTextNode(option));
          field.append(label);
        });
      }
      panel.append(field);
    });
    const error = document.createElement('p');
    error.className = 'wm-ai-interview__error';
    error.hidden = true;
    panel.append(error);
    const answerButton = document.createElement('button');
    answerButton.type = 'button';
    answerButton.className = 'button button--primary wm-ai-interview__submit';
    answerButton.textContent = '답변 보내기';
    answerButton.addEventListener('click', () => {
      const answers = [];
      let firstMissing = null;
      panel.querySelectorAll('.wm-ai-interview__question').forEach((field, index) => {
        const text = field.querySelector('[data-interview-answer="text"]')?.value.trim();
        const choices = [...field.querySelectorAll('[data-interview-answer="choice"]:checked')]
          .map(input => input.value);
        const values = text ? [text] : choices;
        field.classList.toggle('is-invalid', field.dataset.required === 'true' && !values.length);
        if (field.classList.contains('is-invalid') && !firstMissing) firstMissing = field;
        if (values.length) answers.push(`${index + 1}. ${field.dataset.prompt}\n답변: ${values.join(', ')}`);
      });
      if (firstMissing) {
        error.textContent = '필수 질문에 답변해 주세요.';
        error.hidden = false;
        firstMissing.querySelector('textarea, input')?.focus();
        return;
      }
      error.hidden = true;
      submitChatText(`인터뷰 답변\n\n${answers.join('\n\n')}`);
    });
    panel.append(answerButton);
    article.append(panel);
  };

  const setBusy = busy => {
    message.disabled = busy;
    send.disabled = busy;
    send.textContent = busy ? '확인 중' : '전송';
    if (suggestionToggle) suggestionToggle.disabled = busy || selectedScreens.length === 0;
  };

  const render = status => {
    const signature = JSON.stringify({ messages: status.messages, active: status.active });
    if (signature === renderSignature) return;
    renderSignature = signature;
    log.replaceChildren();
    if (!status.messages.length) {
      const welcome = articleOf('AI',
        '선택한 작업 화면을 질문하거나 여러 화면의 공통 수정·연결 작업을 요청해 주세요.', 'DONE');
      log.append(welcome);
    }
    status.messages.forEach((item, messageIndex) => {
      const content = item.state === 'RUNNING' ? '선택한 화면과 연결 관계를 확인하고 있습니다.'
        : (item.state === 'FAILED'
          ? safeFailure(item.failure, '전체 화면 작업을 완료하지 못했습니다. 잠시 후 다시 요청해 주세요.')
          : item.content);
      const article = articleOf(item.role, content, item.state);
      const answered = status.messages.slice(messageIndex + 1).some(message => message.role === 'USER');
      if (item.role === 'AI' && item.state === 'DONE' && !answered) {
        appendInterview(article, item.questions, item.id);
      }
      if (item.state === 'RUNNING' && status.active?.id === item.id) {
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
    setBusy(status.busy === true);
    log.scrollTop = log.scrollHeight;
  };

  suggestionToggle?.addEventListener('click', () => {
    if (suggestionToggle.disabled || !suggestionMenu) return;
    const opening = suggestionMenu.hidden;
    suggestionMenu.hidden = !opening;
    suggestionToggle.setAttribute('aria-expanded', String(opening));
    if (opening) suggestionMenu.querySelector('button')?.focus();
  });
  suggestionMenu?.querySelectorAll('[data-canvas-suggestion]').forEach(button => {
    button.addEventListener('click', () => {
      suggestionMenu.hidden = true;
      suggestionToggle?.setAttribute('aria-expanded', 'false');
      submitChatText(button.dataset.canvasSuggestion);
    });
  });
  suggestionMenu?.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    event.preventDefault();
    event.stopPropagation();
    suggestionMenu.hidden = true;
    suggestionToggle?.setAttribute('aria-expanded', 'false');
    suggestionToggle?.focus();
  });
  document.addEventListener('click', event => {
    if (!suggestionMenu || suggestionMenu.hidden
        || event.target.closest('.wm-ai-chat__suggestion-control')) return;
    suggestionMenu.hidden = true;
    suggestionToggle?.setAttribute('aria-expanded', 'false');
  });

  const refresh = async () => {
    try {
      const response = await fetch(panel.dataset.statusUrl,
        { headers: { 'Accept': 'application/json' }, cache: 'no-store' });
      if (!response.ok) return;
      const status = await response.json();
      const completedRequestId = activeRequestId && !status.active ? activeRequestId : null;
      activeRequestId = status.active?.id || null;
      render(status);
      if (completedRequestId && window.opener && !window.opener.closed) {
        window.opener.postMessage({ type: 'frd-canvas-chat-completed',
          requestId: completedRequestId, screenCount: status.screenCount }, window.location.origin);
      }
    } catch (_) {
      // 다음 실시간 알림이나 자동 확인에서 다시 시도한다.
    }
  };

  form.addEventListener('submit', async event => {
    event.preventDefault();
    const request = message.value.trim();
    if (!request || send.disabled) return;
    setBusy(true);
    try {
      const csrf = form.querySelector('input[name="_csrf"]');
      const headers = { 'Content-Type': 'application/json' };
      if (csrf) headers['X-CSRF-TOKEN'] = csrf.value;
      const response = await fetch(panel.dataset.sendUrl, {
        method: 'POST', headers,
        body: JSON.stringify({ message: request, screenIds: selectedScreens.map(screen => screen.id) })
      });
      const started = await response.json().catch(() => null);
      if (!response.ok) throw new Error(started?.message || started?.detail || '전체 화면 요청을 시작하지 못했습니다.');
      activeRequestId = started.messageId;
      message.value = '';
      await refresh();
    } catch (error) {
      log.append(articleOf('AI', error.message || '전체 화면 요청을 시작하지 못했습니다.', 'FAILED'));
      setBusy(false);
    }
  });

  message.addEventListener('keydown', event => {
    if (event.key !== 'Enter' || event.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    if (event.altKey) {
      const start = message.selectionStart;
      message.setRangeText('\n', start, message.selectionEnd, 'end');
      return;
    }
    if (!send.disabled) form.requestSubmit();
  });

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
      if (!response.ok) throw new Error('전체 화면 작업을 중단하지 못했습니다. 잠시 후 다시 시도해 주세요.');
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

  document.getElementById('clear-frd-canvas-chat-window-selection')?.addEventListener('click', () => {
    selectedScreens = [];
    renderSelection();
    if (window.opener && !window.opener.closed) {
      window.opener.postMessage({ type: 'frd-canvas-selection-cleared' }, window.location.origin);
    }
    message.focus();
  });

  window.addEventListener('message', event => {
    if (event.origin !== window.location.origin || event.source !== window.opener
        || event.data?.type !== 'frd-canvas-selection-changed') return;
    selectedScreens = Array.isArray(event.data.screens) ? event.data.screens
      .filter(screen => screen?.id).map(screen => ({ id: String(screen.id), name: String(screen.name || screen.id) })) : [];
    renderSelection();
    message.focus();
  });

  if (window.opener && !window.opener.closed) {
    window.opener.postMessage({ type: 'frd-canvas-chat-ready' }, window.location.origin);
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
