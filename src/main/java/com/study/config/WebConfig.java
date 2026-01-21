package com.study.config;

import com.study.interceptor.TimeZoneInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private TimeZoneInterceptor timeZoneInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 添加时区拦截器，拦截所有请求
        registry.addInterceptor(timeZoneInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/error");
    }
}
