(() => {
  const markup = `
    <button class="claude-fab" type="button" aria-label="Claude Code와 요구사항 수정" aria-controls="claude-chat" aria-expanded="false">
      <img src="claudecode-color.png" alt="">
    </button>
    <section class="claude-overlay" id="claude-chat" role="dialog" aria-labelledby="claude-chat-title" hidden>
      <header class="claude-overlay__head">
        <img src="claudecode-color.png" alt="">
        <div><strong id="claude-chat-title">Claude Code</strong></div>
        <div class="claude-overlay__actions">
          <button class="claude-overlay__expand" type="button" aria-label="별도 창으로 열기" title="별도 창으로 열기"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 5h5v5M19 5l-7 7M10 19H5v-5M5 19l7-7"/></svg></button>
          <button class="claude-overlay__close" type="button" aria-label="대화창 닫기">×</button>
        </div>
      </header>
      <div class="claude-overlay__log" aria-live="polite">
        <article class="claude-message claude-message--user"><strong>이영희</strong><p>자동 저장은 별도 요구사항으로 두고 직접 임시 저장만 남겨 줘.</p></article>
        <article class="claude-message claude-message--ai"><strong>Claude Code</strong><p>직접 임시 저장과 저장 뒤 작성 계속하기만 남겼습니다. 자동 저장은 <span class="num">REQ-042</span>로 연결했습니다.</p></article>
      </div>
      <form class="claude-overlay__composer" action="#">
        <textarea id="claude-message" aria-label="메시지" placeholder="예: 임시저장함도 관련 화면 후보에 추가해 줘"></textarea>
        <button class="button button--primary" type="submit">전송</button>
      </form>
    </section>`;

  document.body.insertAdjacentHTML('beforeend', markup);
  const fab = document.querySelector('.claude-fab');
  const panel = document.querySelector('.claude-overlay');
  const close = document.querySelector('.claude-overlay__close');
  const composer = document.querySelector('.claude-overlay__composer');
  const actionArea = document.querySelector('.rq-head__actions');

  if (actionArea) actionArea.append(fab);

  const openPanel = () => {
    panel.hidden = false;
    fab.setAttribute('aria-expanded', 'true');
    panel.querySelector('textarea').focus();
  };
  const closePanel = () => {
    panel.hidden = true;
    fab.setAttribute('aria-expanded', 'false');
    fab.focus();
  };

  fab.addEventListener('click', () => panel.hidden ? openPanel() : closePanel());
  close.addEventListener('click', closePanel);
  composer.addEventListener('submit', event => event.preventDefault());
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !panel.hidden) closePanel();
  });
  window.setupClaudeWindow?.({ context: 'requirement' });
})();
