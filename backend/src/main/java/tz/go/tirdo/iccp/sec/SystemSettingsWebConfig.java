package tz.go.tirdo.iccp.sec;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SystemSettingsWebConfig implements WebMvcConfigurer {
    private final FeatureAvailabilityInterceptor features;
    public SystemSettingsWebConfig(FeatureAvailabilityInterceptor features){this.features=features;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(features).addPathPatterns("/api/v1/**").excludePathPatterns("/api/v1/system-settings/public","/api/v1/public/**","/api/v1/dev/**");}
}
