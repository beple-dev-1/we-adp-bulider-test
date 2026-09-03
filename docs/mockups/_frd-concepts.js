(() => {
  if (!document.querySelector('.rq-head__actions')) return;
  const markup = `
    <button class="claude-fab" type="button" aria-label="Claude Code와 FRD 수정" aria-controls="claude-chat" aria-expanded="false"><img src="claudecode-color.png" alt=""></button>
    <section class="claude-overlay" id="claude-chat" role="dialog" aria-labelledby="claude-chat-title" hidden>
      <header class="claude-overlay__head"><img src="claudecode-color.png" alt=""><div><strong id="claude-chat-title">Claude Code</strong><span>FRD 수정 대화</span></div><div class="claude-overlay__actions"><button class="claude-overlay__expand" type="button" aria-label="별도 창으로 열기" title="별도 창으로 열기"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 5h5v5M19 5l-7 7M10 19H5v-5M5 19l7-7"/></svg></button><button class="claude-overlay__close" type="button" aria-label="대화창 닫기">×</button></div></header>
      <div class="claude-overlay__log" aria-live="polite"><article class="claude-message claude-message--user"><strong>이영희</strong><p>임시 저장 버튼은 결재 요청보다 덜 강조하고 자동 저장 시각을 본문 아래에 보여 줘.</p></article><article class="claude-message claude-message--ai"><strong>Claude Code</strong><p>임시 저장을 보조 버튼으로 변경하고 본문 아래에 자동 저장 상태와 시각을 배치했습니다.</p></article></div>
      <form class="claude-overlay__composer" action="#"><div class="wm-selection-context" id="mockup-selection-context" hidden><span><small>선택한 영역</small><strong id="mockup-selection-label"></strong></span><button type="button" id="clear-mockup-selection">선택 해제</button></div><textarea id="work-mockup-claude-message" aria-label="메시지" placeholder="예: 자동 저장 상태를 제목 아래로 옮겨 줘"></textarea><button class="button button--primary" type="submit">전송</button></form>
    </section>`;
  document.body.insertAdjacentHTML('beforeend', markup);
  const button = document.querySelector('.claude-fab');
  const panel = document.querySelector('.claude-overlay');
  const close = document.querySelector('.claude-overlay__close');
  document.querySelector('.rq-head__actions')?.append(button);
  const closePanel = () => { panel.hidden = true; button.setAttribute('aria-expanded', 'false'); button.focus(); };
  button.addEventListener('click', () => { panel.hidden = !panel.hidden; button.setAttribute('aria-expanded', String(!panel.hidden)); if (!panel.hidden) panel.querySelector('textarea').focus(); });
  close.addEventListener('click', closePanel);
  panel.querySelector('form').addEventListener('submit', event => event.preventDefault());
  document.addEventListener('keydown', event => { if (event.key === 'Escape' && !panel.hidden) closePanel(); });
  window.setupClaudeWindow?.({ context: 'work-mockup' });
})();

(() => {
  const analyzeButton = document.getElementById('frd-analyze');
  const stepTwo = document.getElementById('wizard-step-2');
  if (!analyzeButton || !stepTwo) return;
  analyzeButton.addEventListener('click', () => {
    stepTwo.hidden = false;
    stepTwo.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
})();
