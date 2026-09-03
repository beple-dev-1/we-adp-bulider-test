(() => {
  const markup = `
    <button class="claude-fab" type="button" aria-label="Claude Code와 BRD 수정" aria-controls="claude-chat" aria-expanded="false"><img src="claudecode-color.png" alt=""></button>
    <section class="claude-overlay" id="claude-chat" role="dialog" aria-labelledby="claude-chat-title" hidden>
      <header class="claude-overlay__head"><img src="claudecode-color.png" alt=""><div><strong id="claude-chat-title">Claude Code</strong><span>BRD 초안 수정 대화</span></div><div class="claude-overlay__actions"><button class="claude-overlay__expand" type="button" aria-label="별도 창으로 열기" title="별도 창으로 열기"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 5h5v5M19 5l-7 7M10 19H5v-5M5 19l7-7"/></svg></button><button class="claude-overlay__close" type="button" aria-label="대화창 닫기">×</button></div></header>
      <div class="claude-overlay__log" aria-live="polite"><article class="claude-message claude-message--user"><strong>이영희</strong><p>결재 문서 상세는 제외하고 임시 저장 문서함에서 바로 이어쓰게 해 줘.</p></article><article class="claude-message claude-message--ai"><strong>Claude Code</strong><p>결재 문서 상세를 작업 범위에서 제외하고 임시 저장 문서함의 변경 내용에 이어쓰기를 반영했습니다.</p></article></div>
      <form class="claude-overlay__composer" action="#"><textarea id="brd-claude-message" aria-label="메시지" placeholder="예: 화면 외 구현 요건에서 중복 저장 방지를 제외해 줘"></textarea><button class="button button--primary" type="submit">전송</button></form>
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
  window.setupClaudeWindow?.({ context: 'brd' });
})();
