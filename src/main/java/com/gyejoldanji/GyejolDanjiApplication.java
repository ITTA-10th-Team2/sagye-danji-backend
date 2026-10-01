package com.gyejoldanji;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class GyejolDanjiApplication {

	public static void main(String[] args) {
		SpringApplication.run(GyejolDanjiApplication.class, args);
	}

}
