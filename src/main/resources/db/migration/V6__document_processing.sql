-- 문서 추출·정리를 실제로 붙이며 드러난 주석의 거짓을 바로잡는다 (2026-08-15).
--
-- V4 는 이 자리를 「서버 소유 AI API」로 적었다. 그런 물건은 저장소에 없었고, 2026-08-15 에
-- 병주가 「올린 기획자 자격」으로 정했다 — 유일한 AI 경로인 CliClaudeRunner 가 그것이다.
--
-- ⛔ V4 를 고쳐서 될 일이 아니다. 이미 나간 마이그레이션이라 손대면 Flyway 체크섬이 깨진다.
-- ⚠ 이 파일은 COMMENT 만 고친다 — 열도 제약도 안 건드린다. V4 가 그릇을 이미 맞게 만들어 뒀다.

comment on table  adk_builder_document_processing_run is
    '받은 문서 하나를 추출하거나 정리한 시도 한 번. 정리는 올린 기획자의 Claude 자격으로 돈다 — 서버가 가진 API 키가 아니다(2026-08-15 병주 결정). ⛔ 재시도는 이전 행을 덮어쓰지 않는다 — 두 번째 시도가 첫 번째를 지우면 무엇이 왜 실패했는지 사라진다. ⚠ ai_run 과 다른 표인 것이 뜻이 있다: ai_run 의 다섯 갈래는 전부 산출물 층 전이이고, 문서 준비는 층을 넘지 않는다.';

comment on column adk_builder_document_processing_run.run_kind is
    '무엇을 한 시도인가. EXTRACT(파일에서 본문 뽑기 — 서버가 한다. 평문은 그대로 읽고 PDF 는 pdftotext 다. AI 가 아니다)·NORMALIZE(뽑은 본문 1차 정리 — 이쪽만 AI 다) 둘 중 하나다.';

comment on column adk_builder_document_processing_run.provider_run_id is
    'claude 가 돌려준 세션 식별자. 저쪽 로그와 맞춰 보는 데 쓴다. 못 받으면 NULL 이다 — 판정에 쓰지 마라.';

comment on column adk_builder_received_document.extracted_content is
    '서버가 파일에서 뽑아낸 글. 평문은 그대로 읽고 PDF 는 pdftotext 로 뽑는다 — AI 가 아니다. 원문을 덮어쓰지 않는다. 직접 입력만 있는 문서는 뽑을 파일이 없어 NULL 로 남는다.';

comment on column adk_builder_received_document.normalized_content is
    'AI 가 1차 정리하고 사람이 확인·수정할 본문. 올린 기획자의 Claude 자격으로 만든다. 파일이나 직접 입력 원문을 덮어쓰지 않는다. ⚠ AI 정리가 실패하면 뽑은 원문이 그대로 여기 앉는다 — 사람이 손으로 고칠 수 있어야 하기 때문이다.';

comment on column adk_builder_received_document.preparation_state is
    '문서 준비의 지금 상태. PENDING(내용 처리 대기)·EXTRACTING(내용 추출 중)·NORMALIZING(내용 정리 중)·REVIEW_REQUIRED(정리 내용 확인)·READY(준비 완료)·FAILED(문서 처리 오류). 직접 입력만 있으면 EXTRACTING 을 건너뛴다. ⛔ FAILED 는 「글자가 안 나온다」의 뜻이지 「AI 가 실패했다」가 아니다 — AI 정리가 실패해도 REVIEW_REQUIRED 로 두어 사람이 손으로 고칠 길을 남긴다. ⛔ 시도 이력은 여기 담지 않는다 — adk_builder_document_processing_run 이 진다.';
