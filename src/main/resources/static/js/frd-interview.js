(() => {
  let eventSource = null;
  let fallbackTimer = null;

  function disconnectProgress() {
    eventSource?.close();
    eventSource = null;
    window.clearInterval(fallbackTimer);
    fallbackTimer = null;
  }

  function scrollToLatest(page) {
    const log = page.querySelector('#interview-log');
    if (!log) return;
    window.requestAnimationFrame(() => { log.scrollTop = log.scrollHeight; });
  }

  function progressItem(step, key) {
    const item = document.createElement('li');
    item.className = 'is-running';
    item.dataset.progressKey = key;
    const icon = document.createElement('i');
    const content = document.createElement('div');
    const title = document.createElement('strong');
    title.textContent = step.text;
    const state = document.createElement('span');
    state.innerHTML = '처리 중<span class="fi-typing" aria-hidden="true"><b></b><b></b><b></b></span>';
    content.append(title, state);
    item.append(icon, content);
    return item;
  }

  function appendProgress(list, step, key) {
    const previous = list.lastElementChild;
    if (previous?.dataset.progressKey) {
      previous.className = 'is-done';
      previous.querySelector('i').textContent = '✓';
      previous.querySelector('span')?.remove();
    } else {
      previous?.remove();
    }
    list.append(progressItem(step, key));
    const log = list.closest('#interview-log');
    if (log) log.scrollTop = log.scrollHeight;
  }

  async function replaceWizard(fragmentUrl) {
    disconnectProgress();
    const response = await fetch(fragmentUrl, {
      headers: { Accept: 'text/html' }, credentials: 'same-origin', cache: 'no-store'
    });
    if (!response.ok) throw new Error('다음 인터뷰 내용을 불러오지 못했습니다.');
    const documentFragment = new DOMParser().parseFromString(await response.text(), 'text/html');
    const replacement = documentFragment.querySelector('.fi-page');
    const current = document.querySelector('.fi-page');
    if (!replacement || !current) throw new Error('다음 인터뷰 화면을 구성하지 못했습니다.');
    current.replaceWith(replacement);
    initialize(replacement);
  }

  function connectProgress(page, live, fragmentUrl) {
    if (!live || live.dataset.progressBound === 'true') return;
    live.dataset.progressBound = 'true';
    const list = live.querySelector('#frd-progress-list');
    if (!list) return;
    let known = Array.from(list.querySelectorAll('[data-progress-key]'))
      .map(item => item.dataset.progressKey);

    async function refresh() {
      try {
        const response = await fetch(live.dataset.progressUrl, {
          headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store'
        });
        if (!response.ok) throw new Error();
        const data = await response.json();
        if (data.state !== 'ANALYZING') {
          await replaceWizard(fragmentUrl);
          return;
        }
        const steps = data.progress || [];
        const keys = steps.map(step => `${step.kind}|${step.text}`);
        const samePrefix = known.length <= keys.length
          && known.every((key, index) => key === keys[index]);
        if (!samePrefix) {
          list.replaceChildren();
          known = [];
        }
        for (let index = known.length; index < steps.length; index += 1) {
          appendProgress(list, steps[index], keys[index]);
          known.push(keys[index]);
        }
      } catch (_error) {
        // 연결이 잠시 끊겨도 다음 SSE 알림이나 보조 확인에서 다시 시도한다.
      }
    }

    disconnectProgress();
    if ('EventSource' in window && live.dataset.eventsUrl) {
      eventSource = new EventSource(live.dataset.eventsUrl);
      eventSource.addEventListener('refresh', refresh);
      eventSource.addEventListener('error', refresh);
    } else {
      fallbackTimer = window.setInterval(refresh, 3000);
    }
    refresh();
  }

  function createProgress(form) {
    const live = document.createElement('section');
    live.className = 'fi-turn-work';
    live.id = 'frd-live-progress';
    live.dataset.progressUrl = form.dataset.progressUrl;
    live.dataset.eventsUrl = form.dataset.eventsUrl;
    live.innerHTML = '<div class="fi-turn-work__head"><span class="fi-turn-work__state"><strong>관련 화면과 구현 범위를 확인하고 있습니다</strong></span></div>'
      + '<ol class="fi-tool-calls" id="frd-progress-list"><li class="is-running"><i></i><div><strong>답변을 반영하고 있습니다</strong><span>처리 중<span class="fi-typing" aria-hidden="true"><b></b><b></b><b></b></span></span></div></li></ol>';
    return live;
  }

  function answerText(form) {
    const selectedType = form.querySelector('input[name="answerType"]:checked');
    if (!selectedType) return '';
    if (selectedType.value === 'DIRECT') {
      return form.querySelector('#frd-direct-text')?.value.trim() || '';
    }
    return form.querySelector('#frd-selected-answer')?.value || '';
  }

  function showAnswer(form, value) {
    const question = form.querySelector('.fic-ai-question');
    const bubble = document.createElement('article');
    bubble.className = 'fic-answer-bubble fic-answer-bubble--pending';
    const label = document.createElement('span');
    label.textContent = '내 답변';
    const content = document.createElement('p');
    content.textContent = value;
    bubble.append(label, content);
    question?.after(bubble);
    form.querySelectorAll('input, textarea, button').forEach(control => { control.disabled = true; });
    const footer = form.querySelector('.fic-actions');
    if (footer) footer.hidden = true;
    const round = form.querySelector('.fic-round');
    const roundState = round ? { className: round.className, html: round.innerHTML } : null;
    if (round) {
      round.className = 'fi-session-state';
      round.innerHTML = '<i></i><span>분석 중</span>';
    }
    const status = document.querySelector('.fic-status');
    const statusText = status?.textContent;
    if (status) status.textContent = '분석 중';
    const live = createProgress(form);
    bubble.after(live);
    scrollToLatest(form.closest('.fi-page'));
    return { bubble, live, round, roundState, status, statusText };
  }

  function showSubmitError(form, pending, message) {
    pending.live.remove();
    pending.bubble.remove();
    form.querySelectorAll('input, textarea, button').forEach(control => { control.disabled = false; });
    const submit = form.querySelector('#frd-answer-submit');
    if (submit && !form.querySelector('input[name="answerType"]:checked')) submit.disabled = true;
    const footer = form.querySelector('.fic-actions');
    if (footer) footer.hidden = false;
    if (pending.round && pending.roundState) {
      pending.round.className = pending.roundState.className;
      pending.round.innerHTML = pending.roundState.html;
    }
    if (pending.status) pending.status.textContent = pending.statusText;
    let error = form.querySelector('.fic-answer-error');
    if (!error) {
      error = document.createElement('p');
      error.className = 'source-error fic-answer-error';
      error.setAttribute('role', 'alert');
      footer?.prepend(error);
    }
    error.textContent = message;
  }

  function bindAnswerForm(page, form) {
    if (form.dataset.interviewBound === 'true') return;
    form.dataset.interviewBound = 'true';
    const selected = form.querySelector('#frd-selected-answer');
    const directBox = form.querySelector('#frd-direct-answer');
    const directText = form.querySelector('#frd-direct-text');
    const submit = form.querySelector('#frd-answer-submit');
    form.querySelectorAll('input[name="answerType"]').forEach(radio => {
      radio.addEventListener('change', () => {
        const direct = radio.value === 'DIRECT';
        directBox.hidden = !direct;
        directText.required = direct;
        selected.value = direct ? '' : radio.parentElement.querySelector('.frd-option-value').value;
        submit.disabled = false;
        if (direct) directText.focus();
      });
    });

    form.addEventListener('submit', async event => {
      if (event.submitter !== submit) return;
      event.preventDefault();
      if (!form.reportValidity()) return;
      const value = answerText(form);
      if (!value) {
        directText?.focus();
        return;
      }
      const body = new FormData(form);
      const pending = showAnswer(form, value);
      try {
        const response = await fetch(form.dataset.asyncAction, {
          method: 'POST', body, credentials: 'same-origin',
          headers: { Accept: 'application/json' }
        });
        const result = await response.json().catch(() => null);
        if (!response.ok || !result?.accepted) {
          throw new Error(result?.message || '답변을 반영하지 못했습니다. 다시 시도해 주세요.');
        }
        connectProgress(page, pending.live, form.dataset.fragmentUrl);
      } catch (error) {
        showSubmitError(form, pending, error.message);
      }
    });
  }

  function bindResultComposer(page) {
    const message = page.querySelector('#frd-result-message');
    const composer = page.querySelector('#frd-result-composer');
    const actions = page.querySelector('#frd-result-actions');
    message?.addEventListener('keydown', event => {
      if (event.key === 'Escape' && !composer.hidden) {
        event.preventDefault();
        message.value = '';
        message.dispatchEvent(new Event('input', { bubbles: true }));
        composer.hidden = true;
        if (actions) actions.hidden = false;
        page.querySelector('.frd-continue-chat')?.focus();
        return;
      }
      if (event.key !== 'Enter' || event.isComposing || event.keyCode === 229) return;
      event.preventDefault();
      if (event.altKey) {
        const start = message.selectionStart;
        message.setRangeText('\n', start, message.selectionEnd, 'end');
        message.dispatchEvent(new Event('input', { bubbles: true }));
        return;
      }
      if (!composer.hidden) composer.requestSubmit();
    });
    page.querySelectorAll('.frd-continue-chat').forEach(button => {
      button.addEventListener('click', () => {
        if (!message || !composer) return;
        if (actions) actions.hidden = true;
        composer.hidden = false;
        message.focus();
      });
    });
  }

  function initialize(page = document.querySelector('.fi-page')) {
    if (!page) return;
    scrollToLatest(page);
    bindResultComposer(page);
    const form = page.querySelector('#frd-answer-form');
    if (!form) return;
    bindAnswerForm(page, form);
    const live = form.querySelector('#frd-live-progress');
    if (live) connectProgress(page, live, form.dataset.fragmentUrl);
  }

  initialize();
  window.addEventListener('pagehide', disconnectProgress, { once: true });
})();
