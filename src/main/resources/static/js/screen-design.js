(() => {
  const dialog = document.getElementById('screen-design-dialog');
  if (!dialog) return;

  let polling;
  const removeLayerState = () => {
    const url = new URL(window.location.href);
    url.searchParams.delete('selectedSystem');
    url.searchParams.delete('selectedScreen');
    window.history.replaceState({}, '', url);
  };
  const close = () => {
    if (polling) window.clearInterval(polling);
    if (dialog.open) dialog.close();
    removeLayerState();
  };

  dialog.querySelectorAll('[data-screen-design-close]').forEach(button => {
    button.addEventListener('click', close);
  });
  dialog.addEventListener('cancel', event => {
    event.preventDefault();
    close();
  });
  dialog.addEventListener('click', event => {
    if (event.target === dialog) close();
  });
  dialog.showModal();
  dialog.querySelector('#screen-design-dialog-title')?.focus({ preventScroll: true });

  if (dialog.dataset.screenDesignPoll !== 'true') return;
  const poll = async () => {
    try {
      const response = await fetch(dialog.dataset.screenDesignStatusUrl, {
        credentials: 'same-origin',
        headers: { Accept: 'application/json' }
      });
      if (response.ok && (await response.json()).complete) window.location.reload();
    } catch (_) {
      // 일시적인 연결 실패는 다음 확인 주기에서 복구한다.
    }
  };
  polling = window.setInterval(poll, 2500);
})();
