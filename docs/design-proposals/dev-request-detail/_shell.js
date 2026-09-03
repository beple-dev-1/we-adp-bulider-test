function 시안(번호, 이름) {
  const 본문 = document.querySelector('#시안본문').content.cloneNode(true);
  document.body.innerHTML = `
    <div class="shell">
      <aside class="side">
        <div class="brand">WE-APP Builder</div>
        <nav class="nav" aria-label="산출물 메뉴">
          <a href="#">FRD 작업</a><a href="#">메뉴구조도</a><a href="#" aria-current="page">개발요청서</a><a href="#">솔루션 템플릿</a><a href="#">기능명세서</a><a href="#">화면설계서</a>
        </nav>
      </aside>
      <div class="main">
        <header class="top"><strong>프로젝트 / G2C</strong><span class="user">시안 ${번호} · ${이름}　 이영희</span></header>
        <main class="page">
          <header class="page-head">
            <div><span class="eyebrow">DR-003</span><h1>전자결재 임시 저장</h1><p>개발요청서 상세 구성 시안 — 실제 구현 전 비교용</p></div>
            <div class="actions"><span class="status">● 대기</span><a class="button" href="#">기준 FRD 보기</a></div>
          </header>
          <div id="proposal-content"></div>
        </main>
      </div>
    </div>`;
  document.querySelector('#proposal-content').appendChild(본문);
}
