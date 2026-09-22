package com.bizplay.builder.devrequest;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.project.ProjectPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 꾸러미 부품 셋을 빈으로 세운다.
 *
 * <p>⭐ <b>세 부품은 일부러 애너테이션이 없다.</b> {@link DevRequestPackage} 와
 * {@link DevRequestDocument} 는 재료를 값으로 받는 순수한 자리라 임시 폴더 하나로 시험되고,
 * {@link DevRequestDeliveryWorkspace} 는 git 만 만진다. 스프링을 모르는 채로 두는 것이
 * 그 시험들을 가볍게 유지한다 — 엮는 일은 여기서 한 자리에 모아 한다.
 */
@Configuration
public class DevRequestPackagingConfig {

    @Bean
    public DevRequestPackage devRequestPackage(GitCommand git, BuilderProperties properties) {
        return new DevRequestPackage(git, properties.checkTimeout());
    }

    @Bean
    public DevRequestDocument devRequestDocument() {
        return new DevRequestDocument();
    }

    @Bean
    public DevRequestDeliveryWorkspace devRequestDeliveryWorkspace(
            ProjectPaths paths, GitCommand git, BuilderProperties properties) {
        return new DevRequestDeliveryWorkspace(paths, git, properties.checkTimeout());
    }
}
