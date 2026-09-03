# Stage 2 reviewerAgent 출력 계약

> dev-interview SKILL.md Stage 2 진입 시 lazy Read.
> stack.yaml `review.reviewerAgent` 가 `none` 이 아닌 **에이전트 이름**일 때 적용.
> 모델은 `review.reviewerModel` 이 따로 정한다 (`Agent` 의 `model` 인자).
> `reviewerAgent: none` 이면 Stage 2 skip — 본 파일도 미사용.

---

## Agent 호출 입력

- 브리프 전문 (Stage 1 통과 11섹션 마크다운)
- 워크스페이스 컨텍스트 (루트 `CLAUDE.md` 핵심)
- 대상 프로젝트 가이드 (선택된 프로젝트의 `CLAUDE.md`)

Agent description: **"정성 검토 — 11섹션 브리프 점검 (라운드 N/3)"**.

---

## 점검 관점 (4가지)

1. **정합성** — 섹션 간 모순 (§3 영향테이블 ↔ §4 매핑 ↔ §5 코드값 일관성, §6 외부연동 vs §3-2 Related 누락, §11 구현 순서 vs §3 의존성)
2. **누락 위험** — 도메인 상식 (결제→멱등성, PII→마스킹, 대량→인덱스)
3. **모호성** — 후속 dev-plan 해석 갈리는 표현 ("적절한 시점에", "필요시", "관련 처리", "추후 검토")
4. **dev-plan 적합성** — actionable 수준 (§6 보안 헤더명·알고리즘, §3 파일/패키지 단위, §11 순서 일반론 아님)

---

## 출력 포맷 (검토자 Agent 가 반드시 YAML)

```yaml
등급: GREEN | YELLOW | RED
요약: <1-2문장>
코멘트:
  - 관점: 정합성|누락|모호|dev-plan
    심각도: HIGH|MED|LOW
    위치: §X-Y
    내용: <1-2줄>
재인터뷰_갭:        # RED 일 때만
  - 항목: <갭 설명>
    이유: <왜 인터뷰가 부족했는가>
    사용자_질문: <1줄 질문>
```

---

## 등급 기준

| 등급 | 조건 | 후속 |
|------|------|------|
| **GREEN** | HIGH 0 + dev-plan 적합성 OK | 6.6 사용자 제시 |
| **YELLOW** | HIGH 0, MED/LOW 있음 | 6.6 에서 코멘트와 함께 노출 |
| **RED** | HIGH ≥ 1 또는 dev-plan 부적합 | 재인터뷰 루프 (최대 3라운드) |
