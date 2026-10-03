package com.gyejoldanji.global.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** 실제 application.yml의 placeholder를 주어진 환경변수 값만으로 해석하게 한다. OS 환경변수와 .env import는 읽지 않는다. */
public final class TestApplicationYaml {

    private TestApplicationYaml() {
    }

    /** ApplicationContextRunner에 넣을 초기화기. */
    public static ApplicationContextInitializer<ConfigurableApplicationContext> withEnvironment(
            Map<String, Object> environment) {
        return context -> {
            MutablePropertySources sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.addLast(new MapPropertySource("testEnvironment", environment));
            try {
                new YamlPropertySourceLoader()
                        .load("application.yml", new ClassPathResource("application.yml"))
                        .forEach(sources::addLast);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
