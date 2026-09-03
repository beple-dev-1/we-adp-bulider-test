(() => {
  const root = document.querySelector('.business-language');
  if (!root) return;

  if (root.dataset.seedRunning === 'true') window.setTimeout(() => window.location.reload(), 4000);

  const assignHeadingIds = container => {
    if (!container) return;
    container.querySelectorAll('h2').forEach((heading, index) => heading.id = `policy-section-${index}`);
  };
  assignHeadingIds(root.querySelector('[data-policy-content]'));
  assignHeadingIds(root.querySelector('[data-policy-editor]'));

  const policyForm = root.querySelector('[data-policy-form]');
  const policyEditor = policyForm?.querySelector('[data-policy-editor]');
  const policyIndex = policyForm?.querySelector('.policy-index');
  const refreshPolicyIndex = () => {
    if (!policyEditor || !policyIndex) return;
    const headings = [...policyEditor.querySelectorAll('h2')];
    headings.forEach((heading, index) => heading.id = `policy-section-${index}`);
    policyIndex.querySelectorAll('[data-policy-index-link]').forEach(link => link.remove());
    headings.forEach((heading, index) => {
      const link = document.createElement('a');
      link.href = `#policy-section-${index}`;
      link.dataset.policyIndexLink = '';
      link.textContent = heading.textContent.trim() || '새 정책 항목';
      policyIndex.append(link);
    });
  };
  root.querySelector('[data-add-policy-section]')?.addEventListener('click', () => {
    const heading = document.createElement('h2');
    heading.textContent = '새 정책 항목';
    const content = document.createElement('p');
    content.append(document.createElement('br'));
    policyEditor.append(heading, content);
    refreshPolicyIndex();
    heading.scrollIntoView({ behavior: 'smooth', block: 'center' });
    policyEditor.focus();
    const selection = window.getSelection();
    const range = document.createRange();
    range.selectNodeContents(heading);
    selection.removeAllRanges();
    selection.addRange(range);
  });
  policyEditor?.addEventListener('input', refreshPolicyIndex);
  policyForm?.addEventListener('submit', () => {
    const lines = [...policyEditor.children].map(node => {
      const text = node.textContent.trim();
      if (node.tagName === 'H2') return `## ${text}`;
      if (node.tagName === 'H3') return `### ${text}`;
      return text;
    }).filter(Boolean);
    policyForm.querySelector('[data-policy-markdown]').value = `${lines.join('\n\n')}\n`;
  });

  const search = root.querySelector('[data-term-search]');
  const termsTable = root.querySelector('[data-terms-table-wrap]');
  const searchEmpty = root.querySelector('[data-term-search-empty]');
  const searchEmptyTitle = root.querySelector('[data-term-search-empty-title]');
  search?.addEventListener('input', () => {
    const keyword = search.value.trim();
    const query = keyword.toLocaleLowerCase('ko');
    let visible = 0;
    root.querySelectorAll('[data-term-row]').forEach(row => {
      const inputs = [...row.querySelectorAll('input')].map(input => input.value).join(' ');
      row.hidden = query && !`${row.textContent} ${inputs}`.toLocaleLowerCase('ko').includes(query);
      if (!row.hidden) visible += 1;
    });
    const noResults = Boolean(query) && visible === 0;
    if (termsTable) termsTable.hidden = noResults;
    if (searchEmpty) searchEmpty.hidden = !noResults;
    if (noResults && searchEmptyTitle) {
      searchEmptyTitle.textContent = `‘${keyword}’에 해당하는 표준용어가 없습니다.`;
    }
  });
})();
