package com.intellidesk.infrastructure.config;

import com.intellidesk.api_key.ApiKeyScopeInterceptor;
import com.intellidesk.infrastructure.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class Phase6WebMvcConfig implements WebMvcConfigurer {

    private final ApiKeyScopeInterceptor apiKeyScopeInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public Phase6WebMvcConfig(ApiKeyScopeInterceptor apiKeyScopeInterceptor,
                              RateLimitInterceptor rateLimitInterceptor) {
        this.apiKeyScopeInterceptor = apiKeyScopeInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyScopeInterceptor)
                .addPathPatterns("/api/**")
                .order(0);
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .order(1);
    }
}