package com.communityfix.security;

import com.communityfix.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.*;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }
    @Bean CorsConfigurationSource cors(@Value("${communityfix.cors-origins}") String origins) {
        var config=new CorsConfiguration(); config.setAllowedOrigins(Arrays.asList(origins.split(","))); config.setAllowedMethods(List.of("GET","POST","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type","Idempotency-Key")); config.setExposedHeaders(List.of("X-Request-Id"));
        var source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**",config); return source;
    }
    @Bean SecurityFilterChain security(HttpSecurity http,JwtFilter jwt,ObjectMapper json,@Value("${springdoc.api-docs.enabled}") boolean docsEnabled) throws Exception {
        http.csrf(c->c.disable()).cors(c->{}).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c->{c.requestMatchers("/api/auth/login","/api/auth/register","/api/auth/registration-communities","/api/auth/wechat-login","/actuator/health/**","/api/files/*/content").permitAll();if(docsEnabled)c.requestMatchers("/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll();c.anyRequest().authenticated();})
            .exceptionHandling(c->c.authenticationEntryPoint((req,res,ex)->{res.setStatus(401);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getWriter(),ApiResponse.error(401,"UNAUTHENTICATED","请先登录"));})
                .accessDeniedHandler((req,res,ex)->{res.setStatus(403);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getWriter(),ApiResponse.error(403,"FORBIDDEN","没有此操作的权限"));}))
            .addFilterBefore(jwt,UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<JwtFilter> jwtRegistration(JwtFilter filter) {
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
}
