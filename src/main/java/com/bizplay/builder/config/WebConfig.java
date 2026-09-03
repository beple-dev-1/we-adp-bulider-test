package com.bizplay.builder.config;

import com.bizplay.builder.web.ProjectContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ⚠ 범위를 넓히지 마라. {@code /projects/**} 밖에는 프로젝트 번호가 없다 —
 * 관리 화면 둘({@code /admin/*})과 로그인 계열 넷이 그렇다.
 * ({@code /admin/projects} 는 이 무늬에 안 걸린다 — 뿌리가 다르다.)
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ProjectContextInterceptor projectContext;

    public WebConfig(ProjectContextInterceptor projectContext) {
        this.projectContext = projectContext;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(projectContext).addPathPatterns("/projects/**");
    }
}
