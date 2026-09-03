function businessDocumentCandidateShell(설정, 현재) {
  틀({ ...설정, 모양: '산출물', 지금: 'design-guide' });
  const guide = [...document.querySelectorAll('.artifact-link')]
    .find((link) => link.textContent.trim() === '디자인가이드');
  if (!guide) return;
  guide.removeAttribute('aria-current');
  const businessLanguage = document.createElement('a');
  businessLanguage.className = 'artifact-link artifact-link--white';
  businessLanguage.href = 현재 === 'glossary' ? '_business-glossary.html' : '_business-policy.html';
  businessLanguage.textContent = '정책·표준용어';
  businessLanguage.setAttribute('aria-current', 'page');
  guide.after(businessLanguage);
}
