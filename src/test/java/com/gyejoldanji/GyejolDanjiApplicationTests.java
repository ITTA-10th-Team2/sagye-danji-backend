package com.gyejoldanji;

import com.gyejoldanji.domain.auth.toss.TossAnonymousAuthClient;
import com.gyejoldanji.global.config.TestJwtKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
		"app.google-sheets.enabled=false",
		"app.cors.allowed-origins=http://localhost:5173",
		// 비밀이 아닌 테스트 값. TossProperties 검증은 그대로 거치고 아래 대체로 keystore 파일은 열지 않는다.
		"toss.app-name=test-app",
		"toss.identity-environment=DEV",
		"toss.mtls.keystore-path=not-opened.p12",
		"toss.mtls.keystore-password=not-a-secret"
})
class GyejolDanjiApplicationTests {

	/** 실제 mTLS 클라이언트는 대체해 인증서 파일을 열지 않는다. */
	@MockitoBean
	private TossAnonymousAuthClient tossAnonymousAuthClient;

	/** 실행 중 만든 테스트 RSA 키쌍과 필수 JWT 설정을 공급한다. 운영 검증은 그대로 거친다. */
	@DynamicPropertySource
	static void jwtKeys(DynamicPropertyRegistry registry) throws Exception {
		TestJwtKeys.register(registry);
	}

	@Test
	void contextLoads() {
	}

}
