package com.msb.solana.gateway.controller;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the administrative gate on every mutating settlement route. The
 * payment filter deliberately passes {@code /api/v1/settlement/**} through so
 * that this interceptor is the single authentication authority for sweeps.
 */
@Configuration
public class SettlementSecurityConfig implements WebMvcConfigurer {

    private final SettlementAdminInterceptor adminInterceptor;

    public SettlementSecurityConfig(SettlementAdminInterceptor adminInterceptor) {
        this.adminInterceptor = adminInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/v1/settlement/**");
    }
}
