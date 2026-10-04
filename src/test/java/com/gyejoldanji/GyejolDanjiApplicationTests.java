package com.gyejoldanji;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.gyejoldanji.domain.auth.scheduler.ExpiredAuthSessionCleanupScheduler;
import com.gyejoldanji.domain.auth.service.ExpiredAuthSessionCleanupService;
import com.gyejoldanji.domain.auth.toss.TossAnonymousAuthClient;
import com.gyejoldanji.global.config.TestJwtKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

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

	/**
	 * 실제 스케줄러·@EnableScheduling은 그대로 두고 정리 서비스만 대체한다. 이 테스트는 .env 등 실행 환경의 DB에 붙으므로 컨텍스트가 03:00 UTC를
	 * 지나도 실제 삭제가 일어나지 않게 한다. 실제 삭제·트랜잭션은 격리 DB의 정리 MySQL 통합 테스트가 검증한다.
	 */
	@MockitoBean
	private ExpiredAuthSessionCleanupService cleanupService;

	/** 실행 중 만든 테스트 RSA 키쌍과 필수 JWT 설정을 공급한다. 운영 검증은 그대로 거친다. */
	@DynamicPropertySource
	static void jwtKeys(DynamicPropertyRegistry registry) throws Exception {
		TestJwtKeys.register(registry);
	}

	@Autowired
	private ScheduledTaskHolder scheduledTasks;

	@Test
	void contextLoads() {
	}

	/**
	 * 앱의 기존 @EnableScheduling·component scan으로 Google Sheets가 꺼져 있어도 인증 정리만 매일 03:00 UTC로 등록된다. 등록된 작업을 실행하면
	 * 대체한 서비스만 불린다(실제 삭제 없음).
	 */
	@Test
	void registersExpiredSessionCleanupWithoutGoogleSheets() {
		assertThat(scheduledTasks.getScheduledTasks()).singleElement().satisfies(scheduled -> {
			assertThat(scheduled.getTask()).isInstanceOf(CronTask.class);
			CronTask task = (CronTask) scheduled.getTask();
			assertThat(task.getRunnable().toString())
					.isEqualTo(ExpiredAuthSessionCleanupScheduler.class.getName() + ".cleanUp");
			assertThat(task.getExpression()).isEqualTo("0 0 3 * * *");
			assertThat(next(task, "2026-10-04T00:00:00Z")).isEqualTo("2026-10-04T03:00:00Z");
			assertThat(next(task, "2026-10-04T18:00:00Z")).as("KST 03:00이 아니다").isEqualTo("2026-10-05T03:00:00Z");

			task.getRunnable().run();
		});
		verify(cleanupService).cleanUp();
	}

	private static Instant next(CronTask task, String now) {
		return task.getTrigger().nextExecution(new SimpleTriggerContext(Clock.fixed(Instant.parse(now), ZoneOffset.UTC)));
	}

}
