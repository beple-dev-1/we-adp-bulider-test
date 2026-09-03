(() => {
  const markup = `
    <button class="claude-fab" type="button" aria-label="Claude Code와 정의서 수정" aria-controls="claude-chat" aria-expanded="false">
      <img src="claudecode-color.png" alt="">
    </button>
    <section class="claude-overlay" id="claude-chat" role="dialog" aria-labelledby="claude-chat-title" hidden>
      <header class="claude-overlay__head">
        <img src="claudecode-color.png" alt="">
        <div><strong id="claude-chat-title">Claude Code</strong><span>요구사항정의서 수정 대화</span></div>
        <div class="claude-overlay__actions">
          <button class="claude-overlay__expand" type="button" aria-label="별도 창으로 열기" title="별도 창으로 열기"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 5h5v5M19 5l-7 7M10 19H5v-5M5 19l7-7"/></svg></button>
          <button class="claude-overlay__close" type="button" aria-label="대화창 닫기">×</button>
        </div>
      </header>
      <div class="claude-overlay__log" aria-live="polite">
        <article class="claude-message claude-message--user"><strong>이영희</strong><p>저장 주기는 마지막 변경 시점을 기준으로 명확하게 써 줘.</p></article>
        <article class="claude-message claude-message--ai"><strong>Claude Code</strong><p>적용 조건을 ‘마지막 내용 변경 후 30초가 지난 경우’로 구체화했습니다. 저장 실패 처리는 제외 범위로 유지했습니다.</p></article>
      </div>
      <form class="claude-overlay__composer" action="#">
        <textarea id="definition-claude-message" aria-label="메시지" placeholder="예: 기대 결과를 검증 가능한 문장으로 바꿔 줘"></textarea>
        <button class="button button--primary" type="submit">전송</button>
      </form>
    </section>`;

  document.body.insertAdjacentHTML('beforeend', markup);
  const button = document.querySelector('.claude-fab');
  const panel = document.querySelector('.claude-overlay');
  const close = document.querySelector('.claude-overlay__close');
  const actionArea = document.querySelector('.rq-head__actions');
  if (actionArea) actionArea.append(button);

  const closePanel = () => {
    panel.hidden = true;
    button.setAttribute('aria-expanded', 'false');
    button.focus();
  };
  button.addEventListener('click', () => {
    panel.hidden = !panel.hidden;
    button.setAttribute('aria-expanded', String(!panel.hidden));
    if (!panel.hidden) panel.querySelector('textarea').focus();
  });
  close.addEventListener('click', closePanel);
  panel.querySelector('form').addEventListener('submit', event => event.preventDefault());
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !panel.hidden) closePanel();
  });
  window.setupClaudeWindow?.({ context: 'definition' });
})();
