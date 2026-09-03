package com.bizplay.builder;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.config.DocumentUnderstandingProperties;
import com.bizplay.builder.config.FlowProperties;
import com.bizplay.builder.config.RequirementAnalysisProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * ⚠ 설정 묶음 둘이 나뉜 것은 일부러다 — {@link BuilderProperties} 는 <b>없으면 서버가 안 뜨는</b> 값이고
 * {@link DocumentUnderstandingProperties} 는 <b>안 정해도 되는</b> 값이다(멀티모달 문서 읽기).
 * 합치면 사내 문서 외부 전송 정책이 서기 전에는 서버가 아예 안 뜬다.
 */
@SpringBootApplication
@EnableConfigurationProperties({BuilderProperties.class, DocumentUnderstandingProperties.class,
        RequirementAnalysisProperties.class, FlowProperties.class})
public class BuilderApplication {
    public static void main(String[] args) {
        SpringApplication.run(BuilderApplication.class, args);
    }
}
