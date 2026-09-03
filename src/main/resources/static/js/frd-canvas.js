(() => {
  const root = document.querySelector('[data-frd-canvas-root]');
  if (!root) return;
  root.querySelector('[data-canvas-facet-select]')?.addEventListener('change', event => {
    event.currentTarget.form.requestSubmit();
  });
  const canvas = root.querySelector('[data-canvas]');
  const world = root.querySelector('[data-canvas-world]');
  const svg = root.querySelector('[data-canvas-connectors]');
  const nodes = [...root.querySelectorAll('[data-canvas-node]')];
  const relationRows = [...root.querySelectorAll('[data-canvas-relations] li')];
  const selectionView = root.querySelector('[data-canvas-selection]');
  const deleteButton = root.querySelector('[data-canvas-delete]');
  const deleteInput = root.querySelector('[data-canvas-delete-id]');
  const compareButton = root.querySelector('[data-canvas-compare]');
  const compareDialog = root.querySelector('[data-canvas-compare-dialog]');
  const compareFrame = root.querySelector('[data-canvas-compare-frame]');
  const compareCloseButton = root.querySelector('[data-canvas-compare-close]');
  const compareScreenName = root.querySelector('[data-canvas-compare-name]');
  const compareScreenId = root.querySelector('[data-canvas-compare-id]');
  const duplicateButton = root.querySelector('[data-canvas-duplicate]');
  const duplicateDialog = root.querySelector('[data-canvas-duplicate-dialog]');
  const relationButton = root.querySelector('[data-canvas-relation]');
  const relationDialog = root.querySelector('[data-canvas-relation-dialog]');
  const autoLayoutButton = root.querySelector('[data-canvas-auto-layout]');
  const relatedToggleButton = root.querySelector('[data-canvas-related-toggle]');
  const detailViewLink = root.querySelector('[data-detail-view-link]');
  const canvasTransition = root.querySelector('[data-canvas-transition]');
  const elementRows = [...root.querySelectorAll('[data-canvas-elements] li')];
  const chat = root.querySelector('#frd-map-chat');
  const chatLog = root.querySelector('[data-canvas-chat-log]');
  const chatForm = root.querySelector('[data-canvas-chat-form]');
  const chatSuggestionToggle = root.querySelector('[data-canvas-suggestion-toggle]');
  const chatSuggestionMenu = root.querySelector('[data-canvas-suggestion-menu]');
  const layoutKey = root.dataset.layoutKey;
  const selected = new Set();
  const positions = new Map();
  let scale = .7;
  let panX = 35;
  let panY = 35;
  let gesture = null;
  let activeMessageId = null;
  let chatReloaded = false;
  let chatPopup = null;
  let chatPopupWatcher = null;
  let chatPopupDirty = false;
  const safeAiFailure = (value, fallback) => {
    const text = String(value || '').trim();
    const technical = /apiStatus|terminalReason|exitCode|API Error|Exception|\bat [\w$.]+\(|[A-Za-z]:\\|^\s*[\[{]/i;
    return !text || text.length > 320 || technical.test(text) ? fallback : text;
  };
  const detailScreenStorageKey = root.dataset.frdId ? `frd-detail-screen:${root.dataset.frdId}` : null;

  function rememberedDetailScreen() {
    if (!detailScreenStorageKey) return null;
    try { return window.sessionStorage.getItem(detailScreenStorageKey); }
    catch (_) { return null; }
  }

  function updateDetailViewLink(screenId) {
    if (!detailViewLink || !screenId) return;
    const node = root.querySelector(`[data-canvas-node="${CSS.escape(screenId)}"]`);
    if (node?.dataset.workTarget !== 'true') return;
    const url = new URL(detailViewLink.href, window.location.href);
    url.searchParams.set('screen', screenId);
    detailViewLink.href = `${url.pathname}${url.search}${url.hash}`;
    if (!detailScreenStorageKey) return;
    try { window.sessionStorage.setItem(detailScreenStorageKey, screenId); }
    catch (_) { /* 브라우저 저장소를 사용할 수 없어도 링크 갱신은 유지한다. */ }
  }

  const saved = (() => {
    try { return JSON.parse(localStorage.getItem(layoutKey) || '{}'); }
    catch (_) { return {}; }
  })();
  if (Number.isFinite(saved.scale)) scale = saved.scale;
  if (Number.isFinite(saved.panX)) panX = saved.panX;
  if (Number.isFinite(saved.panY)) panY = saved.panY;
  let showRelatedScreens = saved.showRelatedScreens !== false;

  function relationGraph(targetIds) {
    const targetSet = new Set(targetIds);
    const neighbors = new Map(targetIds.map(id => [id, new Set()]));
    relationRows.forEach(row => {
      const source = row.dataset.source;
      const target = row.dataset.target;
      if (row.dataset.state === 'REMOVED' || source === target
          || !targetSet.has(source) || !targetSet.has(target)) return;
      neighbors.get(source).add(target);
      neighbors.get(target).add(source);
    });
    return neighbors;
  }

  /** 작업 화면을 뿌리로 삼고 연결 거리가 같은 화면을 같은 단계에 놓는다. */
  function automaticPositions(targetIds = nodes.map(node => node.dataset.canvasNode), preferredRoots = []) {
    const ids = [...new Set(targetIds)].filter(id =>
      nodes.some(node => node.dataset.canvasNode === id));
    const idSet = new Set(ids);
    const neighbors = relationGraph(ids);
    const workRoots = nodes.filter(node => node.dataset.workTarget === 'true'
      && idSet.has(node.dataset.canvasNode)).map(node => node.dataset.canvasNode);
    const roots = [...new Set(preferredRoots.filter(id => idSet.has(id)).length
      ? preferredRoots.filter(id => idSet.has(id)) : workRoots)];
    if (!roots.length && ids.length) roots.push(ids[0]);

    const owner = new Map();
    const level = new Map();
    const visit = rootIds => {
      const queue = [];
      rootIds.forEach(rootId => {
        if (owner.has(rootId)) return;
        owner.set(rootId, rootId);
        level.set(rootId, 0);
        queue.push(rootId);
      });
      while (queue.length) {
        const current = queue.shift();
        [...(neighbors.get(current) || [])].sort().forEach(next => {
          if (owner.has(next)) return;
          owner.set(next, owner.get(current));
          level.set(next, level.get(current) + 1);
          queue.push(next);
        });
      }
    };
    visit(roots);
    ids.slice().sort().forEach(id => { if (!owner.has(id)) visit([id]); });

    const clusters = new Map();
    ids.forEach(id => {
      const rootId = owner.get(id);
      if (!clusters.has(rootId)) clusters.set(rootId, []);
      clusters.get(rootId).push(id);
    });

    const result = new Map();
    let clusterX = 100;
    [...clusters.entries()].forEach(([rootId, clusterIds]) => {
      const layers = new Map();
      clusterIds.forEach(id => {
        const depth = level.get(id) || 0;
        if (!layers.has(depth)) layers.set(depth, []);
        layers.get(depth).push(id);
      });
      const widest = Math.max(1, ...[...layers.values()].map(layer => Math.min(4, layer.length)));
      const clusterWidth = Math.max(440, widest * 520);
      let layerY = 95;
      [...layers.keys()].sort((a, b) => a - b).forEach(depth => {
        const layer = layers.get(depth).sort((a, b) => {
          if (a === rootId) return -1;
          if (b === rootId) return 1;
          return a.localeCompare(b);
        });
        const columns = Math.min(4, layer.length);
        const rows = Math.ceil(layer.length / columns);
        layer.forEach((id, index) => {
          const row = Math.floor(index / columns);
          const itemsInRow = Math.min(columns, layer.length - row * columns);
          const rowWidth = itemsInRow * 440 + Math.max(0, itemsInRow - 1) * 80;
          const column = index % columns;
          result.set(id, {
            x: clusterX + (clusterWidth - rowWidth) / 2 + column * 520,
            y: layerY + row * 455
          });
        });
        layerY += rows * 455 + 130;
      });
      clusterX += clusterWidth + 260;
    });
    return result;
  }

  const automatic = automaticPositions();
  const defaultPosition = (node, index) => automatic.get(node.dataset.canvasNode)
    || { x: 70 + (index % 4) * 530, y: 65 + Math.floor(index / 4) * 465 };
  nodes.forEach((node, index) => {
    const stored = saved.positions?.[node.dataset.canvasNode];
    positions.set(node.dataset.canvasNode, stored && Number.isFinite(stored.x) && Number.isFinite(stored.y)
      ? stored : defaultPosition(node, index));
  });

  function persist() {
    const value = { scale, panX, panY, showRelatedScreens, positions: Object.fromEntries(positions) };
    localStorage.setItem(layoutKey, JSON.stringify(value));
  }

  function visibleNodes() {
    return nodes.filter(node => !node.hidden);
  }

  function renderTransform() {
    world.style.transform = `translate(${panX}px,${panY}px) scale(${scale})`;
    root.querySelector('[data-canvas-zoom-value]').textContent = `${Math.round(scale * 100)}%`;
    window.requestAnimationFrame(hydrateVisiblePreviews);
  }

  function renderNodes() {
    nodes.forEach(node => {
      const point = positions.get(node.dataset.canvasNode);
      node.style.left = `${point.x}px`;
      node.style.top = `${point.y}px`;
    });
    const extents = nodes.map(node => ({ node, ...positions.get(node.dataset.canvasNode) }));
    world.style.width = `${Math.max(1800, ...extents.map(point => point.x + point.node.offsetWidth + 70))}px`;
    world.style.height = `${Math.max(1200, ...extents.map(point => point.y + point.node.offsetHeight + 70))}px`;
    renderRelations();
  }

  function hydrateVisiblePreviews() {
    const candidates = nodes.filter(node => {
      if (node.hidden) return false;
      const frame = node.querySelector('iframe[data-preview-src]:not([src])');
      if (!frame) return false;
      if (node.dataset.workTarget === 'true') return true;
      if (scale < .55) return false;
      const point = positions.get(node.dataset.canvasNode);
      const left = point.x * scale + panX;
      const top = point.y * scale + panY;
      return left < canvas.clientWidth + 250 && top < canvas.clientHeight + 220
        && left + node.offsetWidth * scale > -250 && top + node.offsetHeight * scale > -220;
    }).slice(0, 12);
    candidates.forEach(node => {
      const frame = node.querySelector('iframe[data-preview-src]:not([src])');
      if (!frame) return;
      const preview = frame.closest('.fc-screen-preview');
      preview.classList.add('is-loading');
      preview.setAttribute('aria-busy', 'true');
      frame.addEventListener('load', () => {
        preview.classList.remove('is-loading');
        preview.classList.add('is-loaded');
        preview.removeAttribute('aria-busy');
      }, { once: true });
      frame.src = frame.dataset.previewSrc;
    });
  }

  function svgElement(name, attrs) {
    const element = document.createElementNS('http://www.w3.org/2000/svg', name);
    Object.entries(attrs).forEach(([key, value]) => element.setAttribute(key, value));
    return element;
  }

  function renderRelations() {
    svg.querySelectorAll('[data-dynamic]').forEach(item => item.remove());
    relationRows.forEach(row => {
      const source = root.querySelector(`[data-canvas-node="${CSS.escape(row.dataset.source)}"]`);
      const target = root.querySelector(`[data-canvas-node="${CSS.escape(row.dataset.target)}"]`);
      if (!source || !target || source.hidden || target.hidden) return;
      const from = positions.get(row.dataset.source);
      const to = positions.get(row.dataset.target);
      const sourceCenter = { x: from.x + source.offsetWidth / 2, y: from.y + source.offsetHeight / 2 };
      const targetCenter = { x: to.x + target.offsetWidth / 2, y: to.y + target.offsetHeight / 2 };
      const dx = targetCenter.x - sourceCenter.x;
      const dy = targetCenter.y - sourceCenter.y;
      const vertical = Math.abs(dy) >= Math.abs(dx) * .7;
      const hasReverse = relationRows.some(candidate => candidate !== row
        && candidate.dataset.source === row.dataset.target
        && candidate.dataset.target === row.dataset.source
        && candidate.dataset.state !== 'REMOVED');
      const reciprocalSide = hasReverse
        ? (row.dataset.source.localeCompare(row.dataset.target) < 0 ? -1 : 1)
        : 0;
      const reciprocalOffset = reciprocalSide * 28;
      let sx; let sy; let tx; let ty; let pathData;
      if (vertical) {
        const direction = dy >= 0 ? 1 : -1;
        const sourceSpread = Math.max(-source.offsetWidth * .32,
          Math.min(source.offsetWidth * .32, dx * .18));
        const targetSpread = Math.max(-target.offsetWidth * .25,
          Math.min(target.offsetWidth * .25, dx * .12));
        sx = sourceCenter.x + sourceSpread + reciprocalOffset;
        sy = direction > 0 ? from.y + source.offsetHeight + 4 : from.y - 4;
        tx = targetCenter.x - targetSpread + reciprocalOffset;
        ty = direction > 0 ? to.y - 12 : to.y + target.offsetHeight + 12;
        const bend = Math.max(55, Math.abs(ty - sy) * .42);
        pathData = `M${sx} ${sy} C${sx} ${sy + direction * bend} ${tx} ${ty - direction * bend} ${tx} ${ty}`;
      } else {
        const direction = dx >= 0 ? 1 : -1;
        const sourceSpread = Math.max(-source.offsetHeight * .3,
          Math.min(source.offsetHeight * .3, dy * .18));
        const targetSpread = Math.max(-target.offsetHeight * .24,
          Math.min(target.offsetHeight * .24, dy * .12));
        sx = direction > 0 ? from.x + source.offsetWidth + 4 : from.x - 4;
        sy = sourceCenter.y + sourceSpread + reciprocalOffset;
        tx = direction > 0 ? to.x - 12 : to.x + target.offsetWidth + 12;
        ty = targetCenter.y - targetSpread + reciprocalOffset;
        const bend = Math.max(55, Math.abs(tx - sx) * .42);
        pathData = `M${sx} ${sy} C${sx + direction * bend} ${sy} ${tx - direction * bend} ${ty} ${tx} ${ty}`;
      }
      const sourceWork = source.dataset.workTarget === 'true';
      const targetWork = target.dataset.workTarget === 'true';
      const classes = [];
      if (row.dataset.state === 'ADDED') classes.push('is-added');
      else if (row.dataset.state === 'REMOVED') classes.push('is-removed');
      classes.push(sourceWork && targetWork ? 'is-work-flow' : 'is-context');
      const markerId = row.dataset.state === 'ADDED' ? 'fc-arrow-added'
        : (row.dataset.state === 'REMOVED' ? 'fc-arrow-removed'
          : (sourceWork && targetWork ? 'fc-arrow-current' : 'fc-arrow-context'));
      const path = svgElement('path', {
        d: pathData,
        'marker-end': `url(#${markerId})`, 'data-dynamic': 'true',
        class: `fc-relation-line ${classes.join(' ')}`
      });
      svg.append(path);
      if (sourceWork && row.dataset.state !== 'REMOVED') {
        const hit = svgElement('path', {
          d: pathData, 'data-dynamic': 'true', class: 'fc-relation-hit',
          tabindex: '0', role: 'button',
          'aria-label': `${source.dataset.screenName}에서 ${target.dataset.screenName}(으)로 이동하는 연결 편집`
        });
        const hover = active => path.classList.toggle('is-hovered', active);
        hit.addEventListener('pointerenter', () => hover(true));
        hit.addEventListener('pointerleave', () => hover(false));
        hit.addEventListener('focus', () => hover(true));
        hit.addEventListener('blur', () => hover(false));
        hit.addEventListener('click', event => {
          event.stopPropagation();
          openRelationEditor(row);
        });
        hit.addEventListener('keydown', event => {
          if (event.key !== 'Enter' && event.key !== ' ') return;
          event.preventDefault();
          openRelationEditor(row);
        });
        svg.append(hit);
      }
      const rawLabel = row.dataset.label || row.dataset.kind;
      const genericLabel = !rawLabel || rawLabel === '화면 이동' || rawLabel === '이동';
      const label = genericLabel ? `${nodeName(target)} 열기` : rawLabel;
      if (label && (!genericLabel || row.dataset.state === 'ADDED' || (sourceWork && targetWork))) {
        const text = svgElement('text', { x: (sx + tx) / 2, y: (sy + ty) / 2 - 9, 'text-anchor': 'middle', 'data-dynamic': 'true' });
        text.textContent = label.length > 28 ? label.slice(0, 27) + '…' : label;
        svg.append(text);
      }
    });
  }

  function setScale(next, originX = canvas.clientWidth / 2, originY = canvas.clientHeight / 2) {
    const bounded = Math.max(.12, Math.min(1.25, next));
    const worldX = (originX - panX) / scale;
    const worldY = (originY - panY) / scale;
    panX = originX - worldX * bounded;
    panY = originY - worldY * bounded;
    scale = bounded;
    renderTransform();
    persist();
  }

  function fitNodes(targetNodes, maximumScale = 1) {
    if (!targetNodes.length) return;
    const points = targetNodes.map(node => ({ node, ...positions.get(node.dataset.canvasNode) }));
    const minX = Math.min(...points.map(point => point.x));
    const minY = Math.min(...points.map(point => point.y));
    const maxX = Math.max(...points.map(point => point.x + point.node.offsetWidth));
    const maxY = Math.max(...points.map(point => point.y + point.node.offsetHeight));
    const width = Math.max(1, maxX - minX);
    const height = Math.max(1, maxY - minY);
    scale = Math.max(.12, Math.min(maximumScale,
      Math.min((canvas.clientWidth - 90) / width, (canvas.clientHeight - 120) / height)));
    panX = (canvas.clientWidth - width * scale) / 2 - minX * scale;
    panY = (canvas.clientHeight - height * scale) / 2 - minY * scale;
    renderTransform();
    persist();
  }

  function fit() {
    fitNodes(visibleNodes());
  }

  function fitWorkTargets() {
    const workTargets = nodes.filter(node => node.dataset.workTarget === 'true');
    fitNodes(workTargets.length ? workTargets : nodes, .9);
  }

  function connectedScreenIds(seedId) {
    const ids = nodes.map(node => node.dataset.canvasNode);
    const neighbors = relationGraph(ids);
    const connected = new Set([seedId]);
    const queue = [seedId];
    while (queue.length) {
      const current = queue.shift();
      (neighbors.get(current) || []).forEach(next => {
        if (connected.has(next)) return;
        connected.add(next);
        queue.push(next);
      });
    }
    return [...connected];
  }

  function layoutCenter(ids, layout) {
    const points = ids.map(id => {
      const point = layout.get(id);
      const node = root.querySelector(`[data-canvas-node="${CSS.escape(id)}"]`);
      return point && node ? { ...point, node } : null;
    }).filter(Boolean);
    if (!points.length) return { x: 0, y: 0 };
    return {
      x: (Math.min(...points.map(point => point.x))
        + Math.max(...points.map(point => point.x + point.node.offsetWidth))) / 2,
      y: (Math.min(...points.map(point => point.y))
        + Math.max(...points.map(point => point.y + point.node.offsetHeight))) / 2
    };
  }

  function arrangeAutomatically() {
    const allIds = visibleNodes().map(node => node.dataset.canvasNode);
    const selectedIds = [...selected];
    const targetIds = selectedIds.length > 1 ? selectedIds
      : (selectedIds.length === 1 ? connectedScreenIds(selectedIds[0]) : allIds);
    const preferredRoots = selectedIds.length === 1 ? selectedIds
      : nodes.filter(node => node.dataset.workTarget === 'true' && targetIds.includes(node.dataset.canvasNode))
        .map(node => node.dataset.canvasNode);
    const arranged = automaticPositions(targetIds, preferredRoots);

    if (selectedIds.length) {
      let offsetX; let offsetY;
      if (selectedIds.length === 1) {
        const id = selectedIds[0];
        const before = positions.get(id);
        const after = arranged.get(id);
        offsetX = before.x - after.x;
        offsetY = before.y - after.y;
      } else {
        const before = layoutCenter(selectedIds, positions);
        const after = layoutCenter(selectedIds, arranged);
        offsetX = before.x - after.x;
        offsetY = before.y - after.y;
      }
      arranged.forEach((point, id) => positions.set(id,
        { x: point.x + offsetX, y: point.y + offsetY }));
    } else {
      arranged.forEach((point, id) => positions.set(id, point));
    }

    root.classList.add('is-auto-arranging');
    renderNodes();
    if (selectedIds.length > 1) {
      fitNodes(nodes.filter(node => selected.has(node.dataset.canvasNode)), .9);
    } else if (selectedIds.length === 1) {
      fitNodes(nodes.filter(node => node.dataset.canvasNode === selectedIds[0]), .9);
    } else {
      fitWorkTargets();
    }
    persist();
    window.setTimeout(() => root.classList.remove('is-auto-arranging'), 320);
  }

  function applyRelatedVisibility({ focus = false } = {}) {
    nodes.forEach(node => {
      const related = node.dataset.workTarget !== 'true';
      node.hidden = related && !showRelatedScreens;
      if (node.hidden) selected.delete(node.dataset.canvasNode);
    });
    if (relatedToggleButton) {
      const label = showRelatedScreens ? '관련 화면 숨기기' : '관련 화면 같이 보기';
      relatedToggleButton.setAttribute('aria-pressed', String(showRelatedScreens));
      relatedToggleButton.setAttribute('aria-label', label);
      relatedToggleButton.title = label;
    }
    renderNodes();
    updateSelection();
    persist();
    if (focus) {
      showRelatedScreens ? fit() : fitWorkTargets();
    }
  }

  function selectedChatScreens() {
    return [...selected].map(id => root.querySelector(`[data-canvas-node="${CSS.escape(id)}"]`))
      .filter(node => node?.dataset.workTarget === 'true')
      .map(node => ({ id: node.dataset.canvasNode, name: node.dataset.screenName || node.dataset.canvasNode }));
  }

  function syncChatPopupSelection() {
    if (!chatPopup || chatPopup.closed) return;
    chatPopup.postMessage({ type: 'frd-canvas-selection-changed', screens: selectedChatScreens() },
      window.location.origin);
  }

  function updateSelection() {
    const editable = root.dataset.canvasEditable === 'true';
    nodes.forEach(node => {
      const active = selected.has(node.dataset.canvasNode);
      node.classList.toggle('is-selected', active);
      node.querySelector('.fc-node-select')?.setAttribute('aria-pressed', String(active));
    });
    if (chatSuggestionToggle) {
      chatSuggestionToggle.disabled = selected.size === 0;
      chatSuggestionToggle.title = selected.size
        ? `${selected.size}개 선택 화면의 개선점을 검토합니다`
        : '검토할 화면을 먼저 선택해 주세요';
      if (!selected.size && chatSuggestionMenu) {
        chatSuggestionMenu.hidden = true;
        chatSuggestionToggle.setAttribute('aria-expanded', 'false');
      }
    }
    const selectedNodes = [...selected].map(id =>
      root.querySelector(`[data-canvas-node="${CSS.escape(id)}"]`)).filter(Boolean);
    const selectedCandidate = selectedNodes.length === 1 ? selectedNodes[0] : null;
    const excludedCandidate = selectedCandidate?.dataset.workTarget === 'true' ? selectedCandidate : null;
    if (excludedCandidate) updateDetailViewLink(excludedCandidate.dataset.canvasNode);
    if (compareButton) {
      compareButton.disabled = !excludedCandidate;
      compareButton.title = excludedCandidate
        ? '선택한 화면의 기준 화면과 FRD 수정안 비교'
        : '비교할 작업 대상 화면을 하나 선택해 주세요.';
    }
    if (deleteButton && deleteInput) {
      const newScreenCandidate = excludedCandidate?.dataset.newScreen === 'true' ? excludedCandidate : null;
      const generating = newScreenCandidate?.dataset.screenState === 'GENERATING';
      deleteButton.disabled = !editable || !newScreenCandidate || generating;
      deleteInput.value = newScreenCandidate?.dataset.screenRowId || '';
      deleteButton.title = !editable
        ? '완료된 FRD에서는 신규 화면을 삭제할 수 없습니다.'
        : generating
        ? 'AI 초안을 만드는 중인 신규 화면은 삭제할 수 없습니다.'
        : newScreenCandidate
        ? '선택한 신규 화면 삭제'
        : excludedCandidate
        ? '기존 화면은 삭제할 수 없습니다. 카드의 자물쇠로 다시 잠가 주세요.'
        : '삭제할 신규 화면을 하나 선택해 주세요.';
    }
    if (duplicateButton) {
      const generating = excludedCandidate?.dataset.screenState === 'GENERATING';
      duplicateButton.disabled = !editable || !excludedCandidate || generating;
      duplicateButton.title = !editable
        ? '완료된 FRD에서는 화면을 복제할 수 없습니다.'
        : generating
        ? 'AI 초안을 만드는 중인 화면은 복제할 수 없습니다.'
        : (excludedCandidate ? '선택한 화면을 새 화면으로 복제' : '복제할 작업 대상 화면을 하나 선택해 주세요.');
    }
    if (relationButton) {
      const editableSources = selectedNodes.filter(node => node.dataset.workTarget === 'true'
        && node.dataset.screenState !== 'GENERATING');
      relationButton.disabled = !editable || selectedNodes.length !== 2 || editableSources.length === 0;
      relationButton.title = !editable
        ? '완료된 FRD에서는 화면 연결을 변경할 수 없습니다.'
        : selectedNodes.length !== 2
        ? '연결할 화면을 두 개 선택해 주세요.'
        : (editableSources.length ? '선택한 두 화면 연결' : '연결이 시작되는 작업 화면을 선택해 주세요.');
    }
    if (autoLayoutButton) {
      autoLayoutButton.title = selectedNodes.length > 1
        ? `선택한 ${selectedNodes.length}개 화면을 연결 관계에 따라 자동 정렬`
        : (selectedNodes.length === 1
          ? '선택한 화면을 중심으로 연결 화면 자동 정렬'
          : '작업 대상을 중심으로 연결 관계에 따라 전체 화면 자동 정렬');
    }
    if (!selected.size) {
      selectionView.hidden = true;
      syncChatPopupSelection();
      return;
    }
    const names = [...selected].map(id => {
      const node = root.querySelector(`[data-canvas-node="${CSS.escape(id)}"]`);
      return node?.querySelector('.fc-node-head strong')?.textContent || id;
    });
    selectionView.hidden = false;
    selectionView.querySelector('[data-canvas-selection-label]').textContent = `${selected.size}개 화면 선택`;
    selectionView.querySelector('[data-canvas-selection-names]').textContent = names.join(' · ');
    syncChatPopupSelection();
  }

  function escapeHtml(value) {
    const span = document.createElement('span'); span.textContent = value; return span.innerHTML;
  }

  nodes.forEach(node => {
    const id = node.dataset.canvasNode;
    node.querySelector('.fc-node-select')?.addEventListener('click', event => {
      event.stopPropagation();
      selected.has(id) ? selected.delete(id) : selected.add(id);
      updateSelection();
    });
    node.addEventListener('dblclick', event => {
      if (event.target.closest('a,button')) return;
      node.querySelector('footer a')?.click();
    });
    node.addEventListener('keydown', event => {
      if (event.key === ' ' && !event.target.closest('a,button')) {
        event.preventDefault(); selected.has(id) ? selected.delete(id) : selected.add(id); updateSelection();
      }
    });
    node.querySelector('[data-node-drag-handle]')?.addEventListener('pointerdown', event => {
      if (event.target.closest('button,a')) return;
      const start = positions.get(id);
      gesture = { type: 'node', id, pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, x: start.x, y: start.y };
      event.currentTarget.setPointerCapture(event.pointerId);
      event.preventDefault();
    });
  });

  detailViewLink?.addEventListener('click', event => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.stopPropagation();
    canvasTransition.hidden = false;
    canvas.setAttribute('aria-busy', 'true');
  });

  canvas.addEventListener('pointerdown', event => {
    if (event.target.closest('[data-canvas-node], .fc-relation-hit')) return;
    gesture = { type: 'pan', pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, x: panX, y: panY };
    canvas.setPointerCapture(event.pointerId); canvas.classList.add('is-panning');
  });
  window.addEventListener('pointermove', event => {
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    const dx = event.clientX - gesture.startX;
    const dy = event.clientY - gesture.startY;
    if (gesture.type === 'pan') { panX = gesture.x + dx; panY = gesture.y + dy; renderTransform(); }
    else { positions.set(gesture.id, { x: gesture.x + dx / scale, y: gesture.y + dy / scale }); renderNodes(); }
  });
  window.addEventListener('pointerup', event => {
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    gesture = null; canvas.classList.remove('is-panning'); persist();
  });
  canvas.addEventListener('wheel', event => {
    event.preventDefault();
    const rect = canvas.getBoundingClientRect();
    setScale(scale + (event.deltaY < 0 ? .08 : -.08), event.clientX - rect.left, event.clientY - rect.top);
  }, { passive: false });

  root.querySelector('[data-canvas-zoom-in]').addEventListener('click', () => setScale(scale + .1));
  root.querySelector('[data-canvas-zoom-out]').addEventListener('click', () => setScale(scale - .1));
  root.querySelector('[data-canvas-zoom-reset]').addEventListener('click', () => setScale(.7));
  root.querySelector('[data-canvas-fit]').addEventListener('click', fit);
  autoLayoutButton?.addEventListener('click', arrangeAutomatically);
  relatedToggleButton?.addEventListener('click', () => {
    showRelatedScreens = !showRelatedScreens;
    applyRelatedVisibility({ focus: true });
  });
  const workspaceFocusButton = root.querySelector('[data-canvas-workspace-focus]');
  const setWorkspaceFocus = active => {
    document.body.classList.toggle('is-frd-focus', active);
    workspaceFocusButton.setAttribute('aria-pressed', String(active));
    workspaceFocusButton.setAttribute('aria-label', active ? '전체 화면 원래 크기로' : '전체 화면 확대');
    workspaceFocusButton.title = active ? '전체 화면 원래 크기로 (Esc)' : '전체 화면 확대';
  };
  const navToggle = document.querySelector('[data-nav-toggle]');
  navToggle?.addEventListener('click', event => {
    if (!document.body.classList.contains('is-frd-focus')) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    setWorkspaceFocus(false);
    document.body.classList.remove('is-nav-collapsed');
    navToggle.setAttribute('aria-expanded', 'true');
    navToggle.setAttribute('aria-label', '메뉴 접기');
    navToggle.title = '메뉴 접기';
    try {
      window.localStorage.setItem('builder-nav-collapsed', 'false');
    } catch (error) {
      // 저장소를 사용할 수 없어도 현재 화면에서는 메뉴를 펼친다.
    }
  }, true);
  workspaceFocusButton.addEventListener('click', () => {
    setWorkspaceFocus(!document.body.classList.contains('is-frd-focus'));
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && document.body.classList.contains('is-frd-focus')) {
      setWorkspaceFocus(false);
      workspaceFocusButton.focus();
    }
  });
  root.querySelectorAll('[data-focus-screen]').forEach(button => button.addEventListener('click', () => {
    const node = root.querySelector(`[data-canvas-node="${CSS.escape(button.dataset.focusScreen)}"]`);
    const point = positions.get(button.dataset.focusScreen);
    if (!node || !point) return;
    root.querySelectorAll('[data-focus-screen]').forEach(item => item.classList.toggle('is-current', item === button));
    updateDetailViewLink(button.dataset.focusScreen);
    panX = canvas.clientWidth / 2 - (point.x + node.offsetWidth / 2) * scale;
    panY = canvas.clientHeight / 2 - (point.y + node.offsetHeight / 2) * scale;
    renderTransform(); persist();
  }));

  const rememberedScreen = rememberedDetailScreen();
  const initialDetailScreen = nodes.some(node => node.dataset.canvasNode === rememberedScreen
    && node.dataset.workTarget === 'true')
    ? rememberedScreen
    : nodes.find(node => node.dataset.workTarget === 'true')?.dataset.canvasNode;
  updateDetailViewLink(initialDetailScreen);

  const chatOpenButton = root.querySelector('[data-canvas-chat-open]');
  const chatMessage = chatForm.querySelector('textarea');
  const chatSend = chatForm.querySelector('button[type="submit"]');
  const reopenChatKey = `${layoutKey}:reopen-chat`;
  let chatRenderSignature = '';

  const setChatBusy = busy => {
    chatMessage.disabled = busy;
    chatSend.disabled = busy;
    chatSend.textContent = busy ? '확인 중' : '전송';
    if (chatSuggestionToggle) chatSuggestionToggle.disabled = busy || selected.size === 0;
  };

  function chatArticle(role, content, state) {
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
  }

  const submitChatText = text => {
    const textarea = chatForm.querySelector('textarea');
    if (!text || textarea.disabled) return;
    textarea.value = text;
    chatForm.requestSubmit();
  };

  function appendInterview(article, questions, messageId) {
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
        question.options.forEach((option, optionIndex) => {
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
  }

  function renderChat(status) {
    const signature = JSON.stringify({ messages: status.messages, active: status.active });
    if (signature === chatRenderSignature) return;
    chatRenderSignature = signature;
    chatLog.replaceChildren();
    if (!status.messages.length) {
      const welcome = chatArticle('AI',
        '화면을 여러 개 선택하거나 캔버스 전체를 기준으로 작업을 요청해 주세요. 화면 생성·복사·수정과 연결 변경을 함께 처리할 수 있습니다.',
        'DONE');
      chatLog.append(welcome);
    }
    status.messages.forEach((message, messageIndex) => {
      const content = message.state === 'RUNNING'
        ? '선택한 화면과 연결 관계를 확인하고 있습니다.'
        : (message.state === 'FAILED'
          ? safeAiFailure(message.failure, '전체 화면 작업을 완료하지 못했습니다. 잠시 후 다시 요청해 주세요.')
          : message.content);
      const article = chatArticle(message.role, content, message.state);
      const answered = status.messages.slice(messageIndex + 1).some(item => item.role === 'USER');
      if (message.role === 'AI' && message.state === 'DONE' && !answered) {
        appendInterview(article, message.questions, message.id);
      }
      if (message.state === 'RUNNING' && status.active?.id === message.id) {
        const steps = document.createElement('ol');
        steps.className = 'wm-ai-chat-progress';
        (status.active.progress || []).forEach(step => {
          const item = document.createElement('li');
          item.textContent = step.text;
          steps.append(item);
        });
        article.append(steps);
      }
      chatLog.append(article);
    });
    chatLog.scrollTop = chatLog.scrollHeight;
  }

  chatSuggestionToggle?.addEventListener('click', () => {
    if (chatSuggestionToggle.disabled || !chatSuggestionMenu) return;
    const opening = chatSuggestionMenu.hidden;
    chatSuggestionMenu.hidden = !opening;
    chatSuggestionToggle.setAttribute('aria-expanded', String(opening));
    if (opening) chatSuggestionMenu.querySelector('button')?.focus();
  });
  chatSuggestionMenu?.querySelectorAll('[data-canvas-suggestion]').forEach(button => {
    button.addEventListener('click', () => {
      chatSuggestionMenu.hidden = true;
      chatSuggestionToggle?.setAttribute('aria-expanded', 'false');
      submitChatText(button.dataset.canvasSuggestion);
    });
  });
  chatSuggestionMenu?.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    event.preventDefault();
    event.stopPropagation();
    chatSuggestionMenu.hidden = true;
    chatSuggestionToggle?.setAttribute('aria-expanded', 'false');
    chatSuggestionToggle?.focus();
  });
  document.addEventListener('click', event => {
    if (!chatSuggestionMenu || chatSuggestionMenu.hidden
        || event.target.closest('.wm-ai-chat__suggestion-control')) return;
    chatSuggestionMenu.hidden = true;
    chatSuggestionToggle?.setAttribute('aria-expanded', 'false');
  });

  const reloadCanvasPreservingChat = () => {
    try { sessionStorage.setItem(reopenChatKey, 'true'); } catch (_) { /* 현재 탭에서만 복원을 시도한다. */ }
    window.location.reload();
  };

  async function loadChat() {
    if (!root.dataset.chatStatusUrl) return;
    const previousActiveId = activeMessageId;
    try {
      const response = await fetch(root.dataset.chatStatusUrl,
        { headers: { 'Accept': 'application/json' }, cache: 'no-store' });
      if (!response.ok) return;
      const status = await response.json();
      activeMessageId = status.active?.id || null;
      renderChat(status);
      setChatBusy(status.busy === true);
      if (previousActiveId && !activeMessageId && !chatReloaded) {
        chatReloaded = true;
        window.setTimeout(reloadCanvasPreservingChat, 900);
      }
    } catch (_) {
      if (!chatLog.querySelector('.wm-ai-chat__notice')) {
        const notice = document.createElement('p');
        notice.className = 'wm-ai-chat__notice';
        notice.textContent = '대화 내용을 불러오지 못했습니다. 잠시 후 다시 확인해 주세요.';
        chatLog.prepend(notice);
      }
    }
  }

  const openChat = () => {
    chat.hidden = false;
    chatOpenButton.setAttribute('aria-expanded', 'true');
    loadChat().then(() => chatMessage.focus());
  };
  chatOpenButton.addEventListener('click', openChat);
  const desktopChat = window.matchMedia('(min-width: 821px)');
  const chatHandle = chat.querySelector('.wm-ai-chat__head');
  let chatDrag = null;
  const resetChatPosition = () => {
    chat.style.removeProperty('left');
    chat.style.removeProperty('top');
    chat.style.removeProperty('right');
    chat.style.removeProperty('bottom');
  };
  const moveChat = (left, top) => {
    const gap = 12;
    const maxLeft = Math.max(gap, window.innerWidth - chat.offsetWidth - gap);
    const maxTop = Math.max(gap, window.innerHeight - chat.offsetHeight - gap);
    chat.style.left = `${Math.min(Math.max(left, gap), maxLeft)}px`;
    chat.style.top = `${Math.min(Math.max(top, gap), maxTop)}px`;
  };
  chatHandle?.addEventListener('pointerdown', event => {
    if (!desktopChat.matches || chat.classList.contains('is-expanded') || event.button !== 0
        || event.target.closest('button, a, input, textarea, select')) return;
    const rect = chat.getBoundingClientRect();
    chatDrag = { pointerId: event.pointerId, x: event.clientX, y: event.clientY,
      left: rect.left, top: rect.top };
    chat.style.right = 'auto';
    chat.style.bottom = 'auto';
    moveChat(rect.left, rect.top);
    chat.classList.add('is-dragging');
    chatHandle.setPointerCapture(event.pointerId);
    event.preventDefault();
  });
  chatHandle?.addEventListener('pointermove', event => {
    if (!chatDrag || event.pointerId !== chatDrag.pointerId) return;
    moveChat(chatDrag.left + event.clientX - chatDrag.x,
      chatDrag.top + event.clientY - chatDrag.y);
  });
  const finishChatDrag = event => {
    if (!chatDrag || event.pointerId !== chatDrag.pointerId) return;
    chatDrag = null;
    chat.classList.remove('is-dragging');
    if (chatHandle.hasPointerCapture(event.pointerId)) chatHandle.releasePointerCapture(event.pointerId);
  };
  chatHandle?.addEventListener('pointerup', finishChatDrag);
  chatHandle?.addEventListener('pointercancel', finishChatDrag);
  desktopChat.addEventListener('change', () => {
    if (!desktopChat.matches) resetChatPosition();
  });
  window.addEventListener('resize', () => {
    if (!desktopChat.matches || !chat.style.left || chat.classList.contains('is-expanded')) return;
    moveChat(Number.parseFloat(chat.style.left), Number.parseFloat(chat.style.top));
  });
  const chatExpandButton = root.querySelector('[data-canvas-chat-expand]');
  const openChatPopup = () => {
    if (chatPopup && !chatPopup.closed) {
      syncChatPopupSelection();
      chatPopup.focus();
      return;
    }
    const popupName = `frd-canvas-ai-chat-${root.dataset.layoutKey}`.replace(/[^A-Za-z0-9_-]/g, '-');
    chatPopup = window.open(root.dataset.chatUrl, popupName,
      'popup=yes,width=860,height=900,resizable=yes,scrollbars=yes');
    if (!chatPopup) {
      window.alert('팝업이 차단되었습니다. 브라우저에서 이 사이트의 팝업을 허용해 주세요.');
      return;
    }
    chatPopupDirty = false;
    chat.hidden = true;
    chatOpenButton.setAttribute('aria-expanded', 'false');
    chatPopup.focus();
    window.clearInterval(chatPopupWatcher);
    chatPopupWatcher = window.setInterval(() => {
      if (chatPopup && !chatPopup.closed) return;
      window.clearInterval(chatPopupWatcher);
      chatPopupWatcher = null;
      chatPopup = null;
      if (chatPopupDirty) {
        reloadCanvasPreservingChat();
        return;
      }
      chat.hidden = false;
      chatOpenButton.setAttribute('aria-expanded', 'true');
      loadChat().then(() => chatMessage.focus());
    }, 300);
  };
  chatExpandButton.addEventListener('click', openChatPopup);
  root.querySelector('[data-canvas-chat-close]').addEventListener('click', () => {
    chat.hidden = true; chatOpenButton.setAttribute('aria-expanded', 'false');
    chatOpenButton.focus();
  });

  window.addEventListener('message', event => {
    if (event.origin !== window.location.origin || event.source !== chatPopup) return;
    if (event.data?.type === 'frd-canvas-chat-ready') {
      syncChatPopupSelection();
      return;
    }
    if (event.data?.type === 'frd-canvas-selection-cleared') {
      selected.clear();
      updateSelection();
      return;
    }
    if (event.data?.type === 'frd-canvas-chat-completed') chatPopupDirty = true;
  });

  const openCompare = screenRowId => {
    if (!compareDialog || !compareFrame || !screenRowId) return;
    const node = root.querySelector(`[data-screen-row-id="${CSS.escape(screenRowId)}"]`);
    if (compareScreenName) compareScreenName.textContent = node?.dataset.screenName || '선택 화면';
    if (compareScreenId) compareScreenId.textContent = node?.dataset.canvasNode || '';
    const url = new URL(root.dataset.compareUrl, window.location.href);
    url.searchParams.set('screenRowId', screenRowId);
    url.searchParams.set('embedded', 'true');
    compareFrame.src = url;
    compareDialog.showModal();
  };

  const closeCompare = () => {
    if (compareDialog?.open) compareDialog.close();
  };

  compareDialog?.addEventListener('close', () => {
    if (compareFrame) compareFrame.removeAttribute('src');
  });
  compareCloseButton?.addEventListener('click', closeCompare);
  compareDialog?.addEventListener('click', event => {
    if (event.target === compareDialog) closeCompare();
  });
  window.addEventListener('message', event => {
    if (event.origin !== window.location.origin || event.source !== compareFrame?.contentWindow) return;
    if (event.data?.type === 'frd-canvas-compare-close') closeCompare();
  });

  compareButton?.addEventListener('click', () => {
    const selectedId = selected.size === 1 ? [...selected][0] : null;
    const node = selectedId
      ? root.querySelector(`[data-canvas-node="${CSS.escape(selectedId)}"]`) : null;
    if (!node || node.dataset.workTarget !== 'true' || !node.dataset.screenRowId) return;
    openCompare(node.dataset.screenRowId);
  });

  if (root.dataset.comparisonScreenRowId) openCompare(root.dataset.comparisonScreenRowId);

  duplicateButton?.addEventListener('click', () => {
    const selectedId = selected.size === 1 ? [...selected][0] : null;
    const node = selectedId
      ? root.querySelector(`[data-canvas-node="${CSS.escape(selectedId)}"]`) : null;
    if (!node || !duplicateDialog) return;
    duplicateDialog.querySelector('[data-canvas-duplicate-source-row]').value = node.dataset.screenRowId || '';
    duplicateDialog.querySelector('[data-canvas-duplicate-source-name]').textContent = node.dataset.screenName || selectedId;
    duplicateDialog.querySelector('[data-canvas-duplicate-source-id]').textContent = selectedId;
    const name = duplicateDialog.querySelector('[data-canvas-duplicate-name]');
    name.value = `${node.dataset.screenName || selectedId} 복사본`;
    duplicateDialog.showModal();
    name.focus();
    name.select();
  });
  duplicateDialog?.querySelector('[data-canvas-duplicate-close]')?.addEventListener('click', () => duplicateDialog.close());
  duplicateDialog?.querySelector('[data-canvas-duplicate-cancel]')?.addEventListener('click', () => duplicateDialog.close());
  duplicateDialog?.querySelector('form')?.addEventListener('submit', event => {
    if (!event.defaultPrevented) duplicateDialog.close();
  });
  const nodeOf = id => root.querySelector(`[data-canvas-node="${CSS.escape(id)}"]`);
  const nodeName = node => node?.dataset.screenName || node?.dataset.canvasNode || '';
  const elementsOf = screenId => elementRows.filter(row => row.dataset.screen === screenId);

  function populateRelationElements(screenId, selectedAnchor = '') {
    const select = relationDialog.querySelector('[data-relation-anchor]');
    const help = relationDialog.querySelector('[data-relation-anchor-help]');
    select.replaceChildren(new Option('클릭 요소를 선택하세요', ''));
    elementsOf(screenId).forEach(row => {
      const option = new Option(`[${row.dataset.kind || '요소'}] ${row.dataset.label || row.dataset.anchor}`,
        row.dataset.anchor);
      select.append(option);
    });
    select.value = selectedAnchor;
    help.textContent = select.options.length > 1
      ? '시작 화면에 정의된 요소만 연결할 수 있습니다.'
      : '선택할 요소가 없습니다. 상세 작업에서 클릭 요소를 먼저 정의해 주세요.';
    select.disabled = select.options.length <= 1;
  }

  function applyRelationDirection(sourceId, targetId, selectedAnchor = '') {
    const source = nodeOf(sourceId);
    const target = nodeOf(targetId);
    relationDialog.querySelector('[data-relation-source]').value = sourceId;
    relationDialog.querySelector('[data-relation-target]').value = targetId;
    relationDialog.querySelector('[data-relation-source-name]').textContent = nodeName(source);
    relationDialog.querySelector('[data-relation-source-id]').textContent = sourceId;
    relationDialog.querySelector('[data-relation-target-name]').textContent = nodeName(target);
    relationDialog.querySelector('[data-relation-target-id]').textContent = targetId;
    relationDialog.querySelector('[data-relation-label-preview]').textContent = `${nodeName(target)} 열기`;
    populateRelationElements(sourceId, selectedAnchor);
  }

  function openRelationDialog(firstId, secondId, original = null) {
    if (!relationDialog) return;
    const direction = relationDialog.querySelector('[data-relation-direction]');
    const title = relationDialog.querySelector('[data-canvas-relation-title]');
    const remove = relationDialog.querySelector('[data-canvas-relation-delete]');
    const first = nodeOf(firstId);
    const second = nodeOf(secondId);
    direction.replaceChildren();
    [[first, second], [second, first]].forEach(([source, target]) => {
      if (!source || source.dataset.workTarget !== 'true' || source.dataset.screenState === 'GENERATING') return;
      direction.append(new Option(`${nodeName(source)} → ${nodeName(target)}`,
        `${source.dataset.canvasNode}|${target.dataset.canvasNode}`));
    });
    if (!direction.options.length) return;
    const preferred = original ? `${original.source}|${original.target}` : direction.options[0].value;
    direction.value = [...direction.options].some(option => option.value === preferred)
      ? preferred : direction.options[0].value;
    relationDialog.querySelector('[data-relation-original-source]').value = original?.source || '';
    relationDialog.querySelector('[data-relation-original-target]').value = original?.target || '';
    relationDialog.querySelector('[data-relation-original-anchor]').value = original?.anchor || '';
    title.textContent = original ? '화면 연결 변경' : '화면 연결';
    remove.hidden = !original;
    const [sourceId, targetId] = direction.value.split('|');
    applyRelationDirection(sourceId, targetId, original?.anchor || '');
    relationDialog.showModal();
    relationDialog.querySelector('[data-relation-anchor]').focus();
  }

  function openRelationEditor(row) {
    openRelationDialog(row.dataset.source, row.dataset.target, {
      source: row.dataset.source,
      target: row.dataset.target,
      anchor: row.dataset.anchor
    });
  }

  relationButton?.addEventListener('click', () => {
    if (selected.size !== 2) return;
    const [firstId, secondId] = [...selected];
    openRelationDialog(firstId, secondId);
  });
  relationDialog?.querySelector('[data-relation-direction]')?.addEventListener('change', event => {
    const [sourceId, targetId] = event.target.value.split('|');
    applyRelationDirection(sourceId, targetId);
  });
  relationDialog?.querySelector('[data-canvas-relation-close]')?.addEventListener('click', () => relationDialog.close());
  relationDialog?.querySelector('[data-canvas-relation-cancel]')?.addEventListener('click', () => relationDialog.close());
  relationDialog?.querySelector('[data-canvas-relation-delete]')?.addEventListener('click', event => {
    if (!window.confirm('이 화면 연결을 삭제할까요?')) event.preventDefault();
  });
  relationDialog?.querySelector('form')?.addEventListener('submit', event => {
    if (!event.defaultPrevented) relationDialog.close();
  });
  root.querySelector('[data-canvas-selection-clear]')?.addEventListener('click', () => {
    selected.clear();
    updateSelection();
    chat.querySelector('textarea').focus();
  });

  chatMessage.addEventListener('keydown', event => {
    if (event.key !== 'Enter' || event.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    if (event.altKey) {
      const start = chatMessage.selectionStart;
      chatMessage.setRangeText('\n', start, chatMessage.selectionEnd, 'end');
      return;
    }
    if (!chatSend.disabled) chatForm.requestSubmit();
  });
  let chatCancelPending = false;
  const cancelActiveChat = async () => {
    if (!activeMessageId || chatCancelPending) return;
    chatCancelPending = true;
    setChatBusy(true);
    const running = chatLog.querySelector('.wm-ai-chat-message:last-child p');
    if (running) running.textContent = '전체 화면 작업을 중단하고 변경 내용을 되돌리고 있습니다.';
    try {
      const csrf = chatForm.querySelector('input[name="_csrf"]');
      const headers = {};
      if (csrf) headers['X-CSRF-TOKEN'] = csrf.value;
      const response = await fetch(`${root.dataset.chatUrl}/${encodeURIComponent(activeMessageId)}/cancel`,
        { method: 'POST', headers });
      if (!response.ok) throw new Error('작업을 중단하지 못했습니다. 잠시 후 다시 시도해 주세요.');
      chatReloaded = true;
      await loadChat();
    } catch (error) {
      const notice = document.createElement('p');
      notice.className = 'wm-ai-chat__notice';
      notice.textContent = error.message;
      chatLog.append(notice);
    } finally {
      chatCancelPending = false;
    }
  };
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || chat.hidden) return;
    if (activeMessageId) {
      event.preventDefault();
      event.stopPropagation();
      cancelActiveChat();
    }
  }, true);

  chatForm.addEventListener('submit', async event => {
    event.preventDefault();
    const textarea = chatForm.querySelector('textarea');
    const message = textarea.value.trim();
    if (!message) return;
    chatLog.append(chatArticle('USER', message, 'DONE'));
    const progress = chatArticle('AI', '선택한 화면과 연결 관계를 확인하고 있습니다.', 'RUNNING');
    progress.classList.add('is-progress');
    chatLog.append(progress);
    chatLog.scrollTop = chatLog.scrollHeight;
    textarea.value = '';
    setChatBusy(true);
    try {
      const csrf = chatForm.querySelector('input[name="_csrf"]');
      const headers = { 'Content-Type': 'application/json' };
      if (csrf) headers['X-CSRF-TOKEN'] = csrf.value;
      const response = await fetch(root.dataset.chatUrl, {
        method: 'POST', headers,
        body: JSON.stringify({ message, screenIds: [...selected] })
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.message || '요청을 시작하지 못했습니다.');
      activeMessageId = result.messageId;
      progress.querySelector('p').textContent = result.message || 'AI가 작업을 시작했습니다.';
      loadChat();
    } catch (error) {
      progress.className = 'wm-ai-chat-message wm-ai-chat-message--ai wm-ai-chat-message--error';
      progress.querySelector('p').textContent = error.message;
      setChatBusy(false);
    }
  });

  if ('EventSource' in window && root.dataset.eventsUrl) {
    const events = new EventSource(root.dataset.eventsUrl);
    events.addEventListener('refresh', () => {
      if (!chat.hidden || activeMessageId) loadChat();
    });
    events.addEventListener('error', () => {
      if (!chat.hidden || activeMessageId) loadChat();
    });
    window.addEventListener('pagehide', () => events.close(), { once: true });
  } else {
    const chatFallbackTimer = window.setInterval(() => {
      if (!chat.hidden || activeMessageId) loadChat();
    }, 3000);
    window.addEventListener('pagehide', () => window.clearInterval(chatFallbackTimer), { once: true });
  }

  window.addEventListener('pagehide', () => window.clearInterval(chatPopupWatcher), { once: true });

  try {
    if (sessionStorage.getItem(reopenChatKey) === 'true') {
      sessionStorage.removeItem(reopenChatKey);
      openChat();
    }
  } catch (_) { /* 세션 저장소를 사용할 수 없어도 채팅은 직접 열 수 있다. */ }

  applyRelatedVisibility(); renderTransform();
  if (!saved.positions) window.setTimeout(fitWorkTargets, 0);
})();
