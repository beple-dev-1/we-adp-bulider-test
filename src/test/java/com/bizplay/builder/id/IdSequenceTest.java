package com.bizplay.builder.id;

import com.bizplay.builder.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채번기 — PK 는 숫자가 아니라 0 채운 일곱 자리 글자다.
 *
 * <p>여기서 재는 것은 셋이다: <b>모양</b>(일곱 자리) · <b>안 겹침</b>(같은 번호가 두 번 안 난다) ·
 * <b>정렬</b>(글자로 비교해도 순서가 안 뒤집힌다). 셋째가 이 설계의 존재 이유다 —
 * 폭이 섞이면 문자 비교라서 {@code '9' > '10'} 으로 뒤집힌다.
 */
class IdSequenceTest extends AbstractDbTest {

    @Autowired
    IdSequence ids;

    @Test
    void 일곱_자리_0채움_글자가_난다() {
        assertThat(ids.next(IdSequence.Kind.PROJECT)).matches("^[0-9]{7}$");
    }

    @Test
    void 같은_번호가_두_번_나지_않는다() {
        String first = ids.next(IdSequence.Kind.ACCOUNT);
        String second = ids.next(IdSequence.Kind.ACCOUNT);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 글자로_비교해도_순서가_안_뒤집힌다() {
        String before = ids.next(IdSequence.Kind.PROJECT);
        String after = ids.next(IdSequence.Kind.PROJECT);
        // ⛔ compareTo 다. 숫자로 바꿔서 비교하면 이 테스트가 아무것도 안 잡는다 —
        //    재려는 것이 바로 「글자 그대로 비교해도 되나」이기 때문이다.
        assertThat(before.compareTo(after)).isNegative();
    }

    @Test
    void 주소에서_온_값의_꼴을_잰다() {
        assertThat(IdSequence.isValidId("0000001")).isTrue();
        assertThat(IdSequence.isValidId("1")).isFalse();          // 옛 주소 · 손으로 친 번호
        assertThat(IdSequence.isValidId("00000001")).isFalse();   // 여덟 자리
        assertThat(IdSequence.isValidId("..")).isFalse();         // 경로를 거슬러 오르려는 값
        assertThat(IdSequence.isValidId("000000a")).isFalse();
        assertThat(IdSequence.isValidId(null)).isFalse();
    }
}
