package com.gyejoldanji.domain.auth.scheduler;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;

import com.gyejoldanji.domain.auth.service.ExpiredAuthSessionCleanupService;
import com.gyejoldanji.domain.content.scheduler.SeasonalContentSyncScheduler;
import com.gyejoldanji.domain.recommendation.scheduler.WeeklyRecommendationGenerationScheduler;
import com.gyejoldanji.domain.recommendation.scheduler.WeeklyRecommendationSyncScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.scheduling.support.TaskUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 만료 이력 정리 스케줄 등록과 실패 처리를 DB 없이 확인한다. Spring의 실제 {@code @Scheduled} 처리기로 등록하고, 실제 예정 시각까지
 * 기다리지 않고 trigger 계산과 등록된 작업 실행으로 검증한다. 전체 앱의 component scan 등록은 contextLoads가 확인한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ExpiredAuthSessionCleanupSchedulerTest {

    /** SQL 오류 메시지에 섞일 수 있는 값. 로그에 남으면 안 된다. */
    private static final String SECRET = "Duplicate entry 'SECRET-VALUE'";

    private final ExpiredAuthSessionCleanupService service = mock(ExpiredAuthSessionCleanupService.class);

    /** 앱의 기존 {@code @EnableScheduling}에 해당하는 테스트 설정. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingConfig {
    }

    /**
     * T28: Google Sheets가 꺼져 있어도(Sheets 스케줄러 3개는 미등록) 인증 정리만 매일 03:00 UTC로 등록된다. JVM 기본 시간대가 Asia/Seoul이어도
     * UTC 기준이며(한국 12:00), 기동만으로 정리를 실행하지 않는다.
     */
    @Test
    void registersDailyUtcCronIndependentOfGoogleSheets() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        try {
            runner().run(context -> {
                assertThat(context).hasNotFailed()
                        .doesNotHaveBean(SeasonalContentSyncScheduler.class)
                        .doesNotHaveBean(WeeklyRecommendationGenerationScheduler.class)
                        .doesNotHaveBean(WeeklyRecommendationSyncScheduler.class);
                CronTask task = onlyCronTask(context);
                assertThat(task.getRunnable().toString())
                        .isEqualTo(ExpiredAuthSessionCleanupScheduler.class.getName() + ".cleanUp");
                assertThat(task.getExpression()).isEqualTo("0 0 3 * * *");
                assertThat(next(task, "2026-10-04T00:00:00Z")).isEqualTo("2026-10-04T03:00:00Z");
                assertThat(next(task, "2026-10-04T03:00:00Z")).isEqualTo("2026-10-05T03:00:00Z");
                assertThat(next(task, "2026-10-04T18:00:00Z")).as("KST 03:00이 아니다").isEqualTo("2026-10-05T03:00:00Z");
            });
        } finally {
            TimeZone.setDefault(original);
        }
        verifyNoInteractions(service);
    }

    /**
     * 정리가 실패하면 스케줄러가 메시지를 뺀 타입·스택만 남기고 끝낸다. 실제 스케줄러가 반복 작업에 붙이는 기본 오류 처리(예외 원문 출력)로
     * 넘어가지 않으며, 완료 로그도 남지 않는다.
     */
    @Test
    void failureIsLoggedWithoutMessageAndNotPassedToDefaultErrorHandler(CapturedOutput output) {
        doThrow(new DataIntegrityViolationException(SECRET, new SQLException(SECRET))).when(service).cleanUp();

        runner().run(context -> TaskUtils.decorateTaskWithErrorHandler(onlyCronTask(context).getRunnable(), null, true)
                .run());

        verify(service).cleanUp();
        String logs = output.getAll();
        assertThat(logs).contains("만료 인증 세션 정리 실패", DataIntegrityViolationException.class.getName(),
                SQLException.class.getName());
        assertThat(logs).doesNotContain("SECRET-VALUE", "Unexpected error occurred in scheduled task", "정리 완료");
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withPropertyValues("app.google-sheets.enabled=false")
                .withBean(ExpiredAuthSessionCleanupService.class, () -> service)
                .withUserConfiguration(SchedulingConfig.class, ExpiredAuthSessionCleanupScheduler.class,
                        SeasonalContentSyncScheduler.class, WeeklyRecommendationGenerationScheduler.class,
                        WeeklyRecommendationSyncScheduler.class);
    }

    /** 등록된 예약 작업이 이 cron 하나뿐인지 확인하고 반환한다. */
    private static CronTask onlyCronTask(ApplicationContext context) {
        List<ScheduledTask> tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTask()).isInstanceOf(CronTask.class);
        return (CronTask) tasks.get(0).getTask();
    }

    private static Instant next(CronTask task, String now) {
        return task.getTrigger().nextExecution(
                new SimpleTriggerContext(Clock.fixed(Instant.parse(now), ZoneOffset.UTC)));
    }
}
