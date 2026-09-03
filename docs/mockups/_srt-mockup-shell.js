/* SRT 메뉴 계약 확정 전 목업 전용 표시 도우미. 실제 메뉴 구현에 복사하지 않는다. */
function SRT목업메뉴() {
  const nav = document.querySelector('.app-nav');
  const frd = Array.from(nav?.querySelectorAll('a') ?? []).find((link) => link.textContent.trim() === 'FRD 작업');
  if (!nav || !frd) return;
  frd.removeAttribute('aria-current');
  const srt = document.createElement('a');
  srt.href = '_srt-list.html';
  srt.className = frd.className;
  srt.setAttribute('aria-current', 'page');
  srt.textContent = 'SRT';
  frd.insertAdjacentElement('afterend', srt);
}
