package com.communityfix.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class JsonConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean Jackson2ObjectMapperBuilderCustomizer longIds() {
        return builder->{ var module=new SimpleModule(); module.addSerializer(Long.class,ToStringSerializer.instance); module.addSerializer(Long.TYPE,ToStringSerializer.instance); builder.modulesToInstall(module); };
    }
}
