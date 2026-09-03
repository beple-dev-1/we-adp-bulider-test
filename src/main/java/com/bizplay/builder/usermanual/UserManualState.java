package com.bizplay.builder.usermanual;

/**
 * 매뉴얼 한 장의 만들기 상태.
 *
 * <p><b>청한 순간부터 줄이 선다</b>({@link #RUNNING}) — 줄이 없으면 목록이 「매뉴얼 없음」으로 뜨고
 * 사람이 같은 단추를 또 누른다. 그때 AI 가 두 번 돈다.
 *
 * <p><b>「매뉴얼 없음」은 여기 없다.</b> 그것은 상태가 아니라 <b>줄이 없는 것</b>이다 —
 * 640장 중 안 만든 것에 줄을 깔면 표가 자료 없이 커진다.
 */
public enum UserManualState {

    /** 만드는 중이다. */
    RUNNING("만드는 중"),
    /** 다 만들었다. {@code html} 이 있다. */
    DONE("최신"),
    /** 못 만들었다. 까닭이 {@code failedReason} 에 있다. */
    FAILED("생성 실패");

    private final String label;

    UserManualState(String label) {
        this.label = label;
    }

    /** 화면에 뜨는 말. ⛔ 코드값(이름)과 갈라 둔다. */
    public String label() {
        return label;
    }
}
