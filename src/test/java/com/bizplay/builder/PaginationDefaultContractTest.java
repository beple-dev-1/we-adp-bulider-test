package com.bizplay.builder;

import com.bizplay.builder.devrequest.DevelopmentRequestController;
import com.bizplay.builder.frd.FrdController;
import com.bizplay.builder.intake.IntakeController;
import com.bizplay.builder.intake.RequirementController;
import com.bizplay.builder.solution.SolutionMockupController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaginationDefaultContractTest {

    private static final List<Class<?>> PAGED_CONTROLLERS = List.of(
            FrdController.class,
            IntakeController.class,
            RequirementController.class,
            DevelopmentRequestController.class,
            SolutionMockupController.class);

    @Test
    void 페이징을_사용하는_모든_목록은_기본_크기를_10개로_쓴다() throws Exception {
        for (Class<?> controller : PAGED_CONTROLLERS) {
            Method list = findListMethod(controller);

            assertThat(Arrays.stream(list.getParameterAnnotations())
                    .flatMap(Arrays::stream)
                    .filter(RequestParam.class::isInstance)
                    .map(RequestParam.class::cast)
                    .map(RequestParam::defaultValue))
                    .as(controller.getSimpleName() + "의 요청 기본값")
                    .contains("10");

            Field pageSizes = controller.getDeclaredField("PAGE_SIZES");
            pageSizes.setAccessible(true);
            assertThat(pageSizes.get(null))
                    .as(controller.getSimpleName() + "의 목록 크기 선택지")
                    .isEqualTo(List.of(10, 20, 50, 100));
        }
    }

    private Method findListMethod(Class<?> controller) {
        return List.of(controller.getDeclaredMethods()).stream()
                .filter(method -> method.getName().equals("list"))
                .findFirst()
                .orElseThrow();
    }
}
