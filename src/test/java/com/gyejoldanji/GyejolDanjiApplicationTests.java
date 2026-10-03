package com.gyejoldanji;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "app.google-sheets.enabled=false")
class GyejolDanjiApplicationTests {

	@Test
	void contextLoads() {
	}

}
