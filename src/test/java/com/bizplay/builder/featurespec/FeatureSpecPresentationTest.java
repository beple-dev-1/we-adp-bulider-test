package com.bizplay.builder.featurespec;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 화면 md 원문을 지우지 않고 기획자가 먼저 읽을 위계로 바꾸는 순수 표시 모델 시험. */
class FeatureSpecPresentationTest {

    @Test
    void 라벨과_이동_화면명을_기획자용_행으로_바꾼다() {
        var document = FeatureSpecDocument.parse("""
                --- 화면명세 ---
                화면명: 가맹점 선택
                진입: pt-list 목록 행클릭
                연관: pt-list, 설명이 붙은 값(참고)

                --- IA ---
                - 종류: 모달 / 상위화면: pt-list

                --- 정의 ---
                - 구분: 이동 / 좌표: id=done / 라벨: 선택 완료 / 이동modal: pt-confirm / 앵커: pt-list-e01 / 해설: onclick 함수가 확인 모달을 연다
                - 구분: 항목 / 좌표: id=name / 라벨: 가맹점명 / 앵커: pt-list-e02 / 해설: 선택한 가맹점의 이름을 보여 준다
                """);

        var view = FeatureSpecPresentation.of(document,
                Map.of("pt-confirm", "선택 확인", "pt-list", "가맹점 목록"));

        assertThat(view.actions()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("선택 완료");
            assertThat(item.kindLabel()).isEqualTo("팝업 열기");
            assertThat(item.targetName()).isEqualTo("선택 확인");
            assertThat(item.targetScreenId()).isEqualTo("pt-confirm");
            assertThat(item.linkable()).isTrue();
        });
        assertThat(view.fields()).singleElement().satisfies(item ->
                assertThat(item.title()).isEqualTo("가맹점명"));
        assertThat(view.related()).extracting(FeatureSpecPresentation.Related::linked)
                .containsExactly(true, false);
        assertThat(view.entry()).isEqualTo("가맹점 목록에서 목록 행 선택");
        assertThat(view.parent().label()).isEqualTo("가맹점 목록");
        assertThat(view.parent().linked()).isTrue();
    }

    @Test
    void 라벨이_없으면_해설의_첫_업무_문장을_쓰고_원문은_보존한다() {
        var document = FeatureSpecDocument.parse("""
                --- 정의 ---
                - 구분: 기능 / 좌표: id=save / 앵커: bo-detail-e01 / 해설: 배송지 수정하기 = 저장 (customDataAjax /detailAction, 주소 필수)
                - 구분: 이동 / 앵커: bo-detail-e02 / 이동unresolved: 외부 가맹점 복귀(런타임 URL) / 해설: handleClose → 서버 경유
                """);

        var view = FeatureSpecPresentation.of(document, Map.of());

        assertThat(view.actions()).extracting(FeatureSpecPresentation.Item::title)
                .containsExactly("배송지 수정하기 — 저장", "외부 가맹점 복귀");
        assertThat(view.actions().get(0).sourceDetail())
                .isEqualTo("배송지 수정하기 = 저장 (customDataAjax /detailAction, 주소 필수)");
        assertThat(view.actions().get(1).kindLabel()).isEqualTo("외부 화면 열기");
        assertThat(view.actions().get(1).linkable()).isFalse();
    }

    @Test
    void 네이티브와_미확정_이동을_실제_동작에_맞게_표시한다() {
        var document = FeatureSpecDocument.parse("""
                --- 정의 ---
                - 구분: 이동 / 이동native: dialer / 해설: 고객센터 전화걸기
                - 구분: 이동 / 이동native: external-browser / 해설: 외부 신청 페이지
                - 구분: 이동 / 이동native: secure-keypad / 해설: 카드번호 입력
                - 구분: 이동 / 이동native: qr-scan / 해설: QR코드 스캔
                - 구분: 이동 / 이동unresolved: 브라우저 뒤로(history.back — 대상이 런타임 이력에 달렸다) / 해설: 이전 화면으로
                - 구분: 이동 / 이동unresolved: 런타임 데이터 링크 / 해설: 이벤트 배너
                """);

        var view = FeatureSpecPresentation.of(document, Map.of());

        assertThat(view.actions()).extracting(FeatureSpecPresentation.Item::kindLabel)
                .containsExactly("전화 연결", "외부 브라우저 열기", "보안 키패드 열기", "QR 코드 스캔",
                        "이전 화면으로 이동", "이동 대상 확인 필요");
    }
}
