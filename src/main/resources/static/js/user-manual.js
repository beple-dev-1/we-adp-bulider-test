(() => {
  const dialog = document.getElementById('user-manual-dialog');
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

  dialog.querySelectorAll('[data-user-manual-close]').forEach(button => {
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
  dialog.querySelector('#user-manual-dialog-title')?.focus({ preventScroll: true });

  if (dialog.dataset.userManualPoll === 'true') {
    polling = window.setInterval(() => window.location.reload(), 2500);
  }
})();
