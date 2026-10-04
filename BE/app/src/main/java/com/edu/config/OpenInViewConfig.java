package com.edu.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Open-in-view everywhere except the AI endpoints, which hold no DB connection while waiting on the LLM
 * (the old ai-service ran with open-in-view disabled).
 */
@Configuration
public class OpenInViewConfig implements WebMvcConfigurer {

    private final EntityManagerFactory entityManagerFactory;

    public OpenInViewConfig(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
        interceptor.setEntityManagerFactory(entityManagerFactory);
        registry.addWebRequestInterceptor(interceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/api/coach/**", "/api/ai/**");
    }
}
