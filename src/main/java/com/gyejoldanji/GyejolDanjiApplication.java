package com.gyejoldanji;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing(dateTimeProviderRef = "utcDateTimeProvider")
@SpringBootApplication
public class GyejolDanjiApplication {

	/** 공통 UTC auditing 설정을 포함한 Spring Boot 애플리케이션을 시작한다. */
	public static void main(String[] args) {
		SpringApplication.run(GyejolDanjiApplication.class, args);
	}

}
