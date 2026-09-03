(() => {
  const DESKTOP_QUERY = '(min-width: 821px)';
  const VIEWPORT_GAP = 12;

  window.setupClaudeWindow = ({ context = 'requirement' } = {}) => {
    const panel = document.querySelector('.claude-overlay');
    const handle = panel?.querySelector('.claude-overlay__head');
    const expand = panel?.querySelector('.claude-overlay__expand');
    const fab = document.querySelector('.claude-fab');
    if (!panel || !handle || !expand) return;

    const desktop = window.matchMedia(DESKTOP_QUERY);
    let drag = null;
    let moved = false;

    const clamp = (value, minimum, maximum) => Math.min(Math.max(value, minimum), Math.max(minimum, maximum));
    const keepInViewport = () => {
      if (!moved || !desktop.matches || panel.hidden) return;
      const rect = panel.getBoundingClientRect();
      panel.style.left = `${clamp(rect.left, VIEWPORT_GAP, window.innerWidth - rect.width - VIEWPORT_GAP)}px`;
      panel.style.top = `${clamp(rect.top, VIEWPORT_GAP, window.innerHeight - rect.height - VIEWPORT_GAP)}px`;
    };

    handle.addEventListener('pointerdown', event => {
      if (!desktop.matches || event.button !== 0 || event.target.closest('button, a, input, textarea, select')) return;
      const rect = panel.getBoundingClientRect();
      drag = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, left: rect.left, top: rect.top };
      panel.style.left = `${rect.left}px`;
      panel.style.top = `${rect.top}px`;
      panel.style.right = 'auto';
      panel.style.bottom = 'auto';
      panel.classList.add('is-dragging');
      handle.setPointerCapture(event.pointerId);
      event.preventDefault();
    });

    handle.addEventListener('pointermove', event => {
      if (!drag || event.pointerId !== drag.pointerId) return;
      const maxLeft = window.innerWidth - panel.offsetWidth - VIEWPORT_GAP;
      const maxTop = window.innerHeight - panel.offsetHeight - VIEWPORT_GAP;
      panel.style.left = `${clamp(drag.left + event.clientX - drag.x, VIEWPORT_GAP, maxLeft)}px`;
      panel.style.top = `${clamp(drag.top + event.clientY - drag.y, VIEWPORT_GAP, maxTop)}px`;
      moved = true;
    });

    const finishDrag = event => {
      if (!drag || event.pointerId !== drag.pointerId) return;
      drag = null;
      panel.classList.remove('is-dragging');
      if (handle.hasPointerCapture(event.pointerId)) handle.releasePointerCapture(event.pointerId);
    };
    handle.addEventListener('pointerup', finishDrag);
    handle.addEventListener('pointercancel', finishDrag);

    let popup = null;
    let popupWatcher = null;
    const showLayer = () => {
      panel.hidden = false;
      fab?.setAttribute('aria-expanded', 'true');
    };
    const watchPopupClose = () => {
      window.clearInterval(popupWatcher);
      popupWatcher = window.setInterval(() => {
        if (popup && !popup.closed) return;
        window.clearInterval(popupWatcher);
        popupWatcher = null;
        popup = null;
        showLayer();
      }, 300);
    };

    expand.addEventListener('click', () => {
      const url = new URL('claude-chat-window.html', window.location.href);
      url.searchParams.set('context', context);
      popup = window.open(url, `claude-code-${context}`, 'popup=yes,width=760,height=780,resizable=yes,scrollbars=yes');
      if (!popup) window.alert('팝업이 차단되었습니다. 브라우저에서 이 사이트의 팝업을 허용해 주세요.');
      else {
        panel.hidden = true;
        fab?.setAttribute('aria-expanded', 'false');
        popup.focus();
        watchPopupClose();
      }
    });

    window.addEventListener('beforeunload', () => window.clearInterval(popupWatcher));

    window.addEventListener('resize', keepInViewport);
    desktop.addEventListener('change', () => {
      if (!desktop.matches) {
        panel.style.removeProperty('left');
        panel.style.removeProperty('top');
        panel.style.removeProperty('right');
        panel.style.removeProperty('bottom');
        moved = false;
      }
    });

    if (new URLSearchParams(window.location.search).get('claude') === 'open' && panel.hidden) {
      document.querySelector('.claude-fab')?.click();
    }
  };
})();
