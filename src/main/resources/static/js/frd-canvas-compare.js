(() => {
  const frames = [...document.querySelectorAll('.fc-compare-preview iframe')];
  if (frames.length !== 2) return;

  let syncing = false;
  const boundDocuments = new WeakSet();
  const counterparts = new WeakMap();
  const highlightClasses = [
    'builder-compare-highlight--changed',
    'builder-compare-highlight--added',
    'builder-compare-highlight--removed'
  ];

  const clearHighlights = documentValue => {
    documentValue.querySelectorAll(highlightClasses.map(name => `.${name}`).join(','))
      .forEach(element => element.classList.remove(...highlightClasses));
  };

  const installHighlightStyles = documentValue => {
    if (documentValue.getElementById('builder-compare-highlight-style')) return;
    const style = documentValue.createElement('style');
    style.id = 'builder-compare-highlight-style';
    style.textContent = `
      .builder-compare-highlight--changed {
        outline: 3px dashed #d58b00 !important;
        outline-offset: 2px !important;
        box-shadow: 0 0 0 5px rgb(213 139 0 / 16%) !important;
      }
      .builder-compare-highlight--added {
        outline: 3px solid #3d9254 !important;
        outline-offset: 2px !important;
        box-shadow: 0 0 0 5px rgb(61 146 84 / 14%) !important;
      }
      .builder-compare-highlight--removed {
        outline: 4px double #c94c55 !important;
        outline-offset: 2px !important;
        box-shadow: 0 0 0 5px rgb(201 76 85 / 12%) !important;
      }
    `;
    documentValue.head?.append(style);
  };

  const elementKey = element => element.dataset.elementId || element.id || null;

  const keyedElements = documentValue => {
    const result = new Map();
    documentValue.querySelectorAll('[data-element-id], [id]').forEach(element => {
      if (['SCRIPT', 'STYLE', 'LINK', 'META'].includes(element.tagName)) return;
      const key = elementKey(element);
      if (key && !result.has(key)) result.set(key, element);
    });
    return result;
  };

  const fingerprint = element => {
    const clone = element.cloneNode(true);
    clone.querySelectorAll('script, style').forEach(item => item.remove());
    [clone, ...clone.querySelectorAll('*')].forEach(item => {
      item.classList?.remove(...highlightClasses);
      [...item.attributes].forEach(attribute => {
        if (attribute.name.startsWith('data-builder-compare-')) item.removeAttribute(attribute.name);
      });
    });
    return clone.outerHTML.replace(/\s+/g, ' ').replace(/>\s+</g, '><').trim();
  };

  const mark = (element, kind) => {
    element.classList.add(`builder-compare-highlight--${kind}`);
    element.setAttribute('data-builder-compare-kind', kind);
  };

  const highlightDifferences = () => {
    const baselineDocument = frames[0].contentDocument;
    const draftDocument = frames[1].contentDocument;
    if (!baselineDocument || !draftDocument
        || baselineDocument.readyState !== 'complete' || draftDocument.readyState !== 'complete') return;
    clearHighlights(baselineDocument);
    clearHighlights(draftDocument);
    installHighlightStyles(baselineDocument);
    installHighlightStyles(draftDocument);
    const baseline = keyedElements(baselineDocument);
    const draft = keyedElements(draftDocument);
    new Set([...baseline.keys(), ...draft.keys()]).forEach(key => {
      const before = baseline.get(key);
      const after = draft.get(key);
      if (!before) mark(after, 'added');
      else if (!after) mark(before, 'removed');
      else if (fingerprint(before) !== fingerprint(after)) {
        mark(before, 'changed');
        mark(after, 'changed');
      }
    });
  };

  const scrollingElement = documentValue =>
    documentValue?.scrollingElement || documentValue?.documentElement || null;

  const metrics = element => ({
    horizontal: Math.max(0, element.scrollWidth - element.clientWidth),
    vertical: Math.max(0, element.scrollHeight - element.clientHeight)
  });

  const elementPath = (element, documentValue) => {
    const path = [];
    let current = element;
    while (current && current !== documentValue.body) {
      const parent = current.parentElement;
      if (!parent) return null;
      path.unshift([...parent.children].indexOf(current));
      current = parent;
    }
    return current === documentValue.body ? path : null;
  };

  const followPath = (documentValue, path) => {
    let current = documentValue.body;
    for (const index of path || []) {
      current = current?.children[index];
      if (!current) return null;
    }
    return current;
  };

  const canFollow = (candidate, sourceMetrics) => {
    if (!candidate) return false;
    const candidateMetrics = metrics(candidate);
    return sourceMetrics.horizontal > 0 && candidateMetrics.horizontal > 0
      || sourceMetrics.vertical > 0 && candidateMetrics.vertical > 0;
  };

  const matchingScrollableElement = (sourceElement, sourceDocument, targetDocument) => {
    const sourceMetrics = metrics(sourceElement);
    if (sourceElement === scrollingElement(sourceDocument)) return scrollingElement(targetDocument);

    const cached = counterparts.get(sourceElement);
    if (cached?.isConnected && canFollow(cached, sourceMetrics)) return cached;

    let candidate = sourceElement.id
      ? targetDocument.getElementById(sourceElement.id)
      : null;
    if (!canFollow(candidate, sourceMetrics)) {
      candidate = followPath(targetDocument, elementPath(sourceElement, sourceDocument));
    }
    if (!canFollow(candidate, sourceMetrics) && sourceElement.classList.length > 0) {
      const selector = sourceElement.tagName.toLowerCase()
        + [...sourceElement.classList].map(name => `.${CSS.escape(name)}`).join('');
      candidate = targetDocument.querySelector(selector);
    }
    if (!canFollow(candidate, sourceMetrics)) {
      candidate = [...targetDocument.querySelectorAll('*')].find(item =>
        item.tagName === sourceElement.tagName && canFollow(item, sourceMetrics));
    }
    if (!canFollow(candidate, sourceMetrics)) return null;
    counterparts.set(sourceElement, candidate);
    return candidate;
  };

  const synchronize = (sourceFrame, targetFrame, eventTarget) => {
    if (syncing) return;
    const sourceDocument = sourceFrame.contentDocument;
    const targetDocument = targetFrame.contentDocument;
    if (!sourceDocument || !targetDocument) return;

    const sourceElement = eventTarget?.nodeType === 1
      ? eventTarget
      : scrollingElement(sourceDocument);
    if (!sourceElement) return;
    const targetElement = matchingScrollableElement(sourceElement, sourceDocument, targetDocument);
    if (!targetElement) return;

    const sourceMetrics = metrics(sourceElement);
    const targetMetrics = metrics(targetElement);
    syncing = true;
    if (sourceMetrics.horizontal > 0 && targetMetrics.horizontal > 0) {
      targetElement.scrollLeft = targetMetrics.horizontal
        * (sourceElement.scrollLeft / sourceMetrics.horizontal);
    }
    if (sourceMetrics.vertical > 0 && targetMetrics.vertical > 0) {
      targetElement.scrollTop = targetMetrics.vertical
        * (sourceElement.scrollTop / sourceMetrics.vertical);
    }
    requestAnimationFrame(() => { syncing = false; });
  };

  const bind = (sourceFrame, targetFrame) => {
    const sourceDocument = sourceFrame.contentDocument;
    const sourceView = sourceFrame.contentWindow;
    if (!sourceDocument || !sourceView || boundDocuments.has(sourceDocument)) return;
    boundDocuments.add(sourceDocument);
    sourceDocument.addEventListener('scroll', event =>
      synchronize(sourceFrame, targetFrame, event.target), { capture: true, passive: true });
    sourceView.addEventListener('scroll', () =>
      synchronize(sourceFrame, targetFrame, sourceDocument), { passive: true });
  };

  frames.forEach((frame, index) => {
    frame.addEventListener('load', () => {
      bind(frame, frames[1 - index]);
      requestAnimationFrame(highlightDifferences);
    });
    if (frame.contentDocument?.readyState === 'complete') bind(frame, frames[1 - index]);
  });
  requestAnimationFrame(highlightDifferences);
})();
