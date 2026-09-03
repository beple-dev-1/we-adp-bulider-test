package com.bizplay.builder.config;

import com.bizplay.builder.frd.FrdController;
import com.bizplay.builder.frd.FrdCanvasController;
import com.bizplay.builder.solution.SolutionPreviewController;
import com.bizplay.builder.usermanual.UserManualController;
import com.bizplay.builder.web.FirstLoginFilter;
import com.bizplay.builder.design.DesignFrameController;
import com.bizplay.builder.design.DesignGuideArtifactController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, FirstLoginFilter firstLogin)
            throws Exception {
        RequestMatcher solutionPreview = PathPatternRequestMatcher.withDefaults()
                .matcher(SolutionPreviewController.URL_PATTERN);
        RequestMatcher frdPreview = PathPatternRequestMatcher.withDefaults()
                .matcher(FrdController.PREVIEW_URL_PATTERN);
        RequestMatcher frdHistoryPreview = PathPatternRequestMatcher.withDefaults()
                .matcher(FrdController.HISTORY_PREVIEW_URL_PATTERN);
        RequestMatcher frdCanvasCompare = PathPatternRequestMatcher.withDefaults()
                .matcher(FrdCanvasController.COMPARE_URL_PATTERN);
        // 디자인가이드 산출물은 opaque sandbox 안에서 다시 iframe을 연다.
        // SAMEORIGIN 헤더를 주면 그 안쪽 프리뷰가 자신의 부모(opaque origin)에 막힌다.
        // 그래서 이 경로는 X-Frame-Options를 아예 보내지 않고, 일회성 열쇠와 CSP sandbox로 가둔다.
        RequestMatcher designFrame = PathPatternRequestMatcher.withDefaults()
                .matcher(DesignFrameController.URL_PATTERN);
        RequestMatcher designGuideArtifact = PathPatternRequestMatcher.withDefaults()
                .matcher(DesignGuideArtifactController.URL_PATTERN);
        // 사용자 매뉴얼 보기도 우리 화면에 우리 글을 iframe 으로 끼운다 — 그린존 A2.
        RequestMatcher userManualPreview = PathPatternRequestMatcher.withDefaults()
                .matcher(UserManualController.URL_PATTERN);
        RequestMatcher sameOriginPreview = new OrRequestMatcher(solutionPreview, frdPreview,
                frdHistoryPreview, frdCanvasCompare, designFrame, userManualPreview);
        RequestMatcher frameAllowed = new OrRequestMatcher(sameOriginPreview, designGuideArtifact);

        http
                /*
                 * ⭐ 솔루션 목업 상세·FRD 작업대·화면 비교 레이어는 <b>우리 화면에 우리 파일을 iframe 으로 끼운다.</b>
                 *    기본값 DENY 는 그것까지 막아서 미리보기 칸이 통째로 빈칸이 된다 —
                 *    서버는 200 을 내는데 브라우저가 안 그리는, 로그에 안 남는 고장이다.
                 *
                 * ⛔ 이것을 「전부 SAMEORIGIN」으로 넓히지 마라. 느슨해지는 자리를
                 *    미리보기와 화면 비교 경로로 좁혀 두는 것이 이 두 줄의 값이다.
                 * ⚠ 미리보기가 남의 스크립트를 안 돌리는 것은 이것과 별개다 —
                 *    거기는 iframe sandbox 와 CSP sandbox 가 따로 지킨다.
                 */
                .headers(h -> h
                        .frameOptions(frame -> frame.disable())
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(sameOriginPreview,
                                new XFrameOptionsHeaderWriter(XFrameOptionsMode.SAMEORIGIN)))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new NegatedRequestMatcher(frameAllowed),
                                new XFrameOptionsHeaderWriter(XFrameOptionsMode.DENY))))
                .authorizeHttpRequests(a -> a
                        // 정적 자원 경로는 StaticResources 가 정본이다 — 관문 필터도 같은 것을 쓴다.
                        // 여기만 열고 필터를 안 고치면 관문에 걸린 사람의 요청이 되튕긴다.
                        .requestMatchers("/login").permitAll()
                        .requestMatchers(DesignGuideArtifactController.URL_PATTERN).permitAll()
                        .requestMatchers(StaticResources.patterns()).permitAll()
                        .anyRequest().authenticated())
                .formLogin(f -> f
                        .loginPage("/login")
                        // 로그인 뒤에는 이전에 열었던 주소로 돌아가지 않고 FRD 작업 목록만 기본으로 연다.
                        // 프로젝트가 하나면 /projects 가 바로 FRD 목록으로 보내고, 여러 개면 먼저 고르게 한다.
                        .defaultSuccessUrl("/projects", true)
                        .permitAll())
                .logout(l -> l.logoutSuccessUrl("/login"))
                .addFilterAfter(firstLogin, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
