package com.gyejoldanji.domain.analytics.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.gyejoldanji.domain.analytics.config.AnalyticsSheetSyncProperties;
import com.gyejoldanji.domain.analytics.repository.AnalyticsEventRepository;
import com.gyejoldanji.domain.analytics.service.AnalyticsSheetRowMapper;
import com.gyejoldanji.domain.analytics.service.AnalyticsSheetSyncService;
import com.gyejoldanji.global.infrastructure.google.GoogleSheetsGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 실제 설정 바인딩과 예약 등록을 확인하며 Sheets 인증 파일이나 DB 연결은 만들지 않는다. */
@ExtendWith(OutputCaptureExtension.class)
class AnalyticsSheetSyncSchedulerTest {

    private static final String PREFIX = "app.analytics.google-sheets.";
    private static final String SECRET = "scheduler-test-secret-not-real-123456";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T00:03:00Z"), ZoneOffset.UTC);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class);

    @ParameterizedTest
    @CsvSource({"false,false,0", "false,true,0", "true,false,0", "true,true,1"})
    void registersServiceAndSchedulerOnlyWhenBothFlagsAreTrue(boolean shared, boolean analytics, int count) {
        runner.withUserConfiguration(Dependencies.class)
                .withPropertyValues("app.google-sheets.enabled=" + shared, PREFIX + "enabled=" + analytics)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(AnalyticsSheetSyncService.class)).hasSize(count);
                    assertThat(context.getBeansOfType(AnalyticsSheetSyncScheduler.class)).hasSize(count);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(count);
                });
    }

    @Test
    void defaultsToDisabledWithoutSyncDependencies() {
        runner.run(context -> {
            assertThat(context).hasNotFailed()
                    .doesNotHaveBean(AnalyticsSheetSyncService.class)
                    .doesNotHaveBean(AnalyticsSheetSyncScheduler.class)
                    .doesNotHaveBean(GoogleSheetsGateway.class)
                    .doesNotHaveBean(AnalyticsEventRepository.class)
                    .doesNotHaveBean(PlatformTransactionManager.class);
            assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty();
        });
    }

    @Test
    void registersDefaultTenMinuteScheduleAndInvokesRealService() {
        enabledRunner().run(context -> {
            assertThat(context).hasNotFailed();
            CronTask task = task(context);
            assertThat(task.getExpression()).isEqualTo("0 */10 * * * *");
            assertThat(next(task)).isEqualTo(Instant.parse("2026-10-12T00:10:00Z"));
            GoogleSheetsGateway gateway = context.getBean(GoogleSheetsGateway.class);
            when(gateway.readValues("test-spreadsheet", "'analytics_events'!A:A"))
                    .thenReturn(List.of(List.of("event_id")));

            task.getRunnable().run();

            verify(gateway).readValues("test-spreadsheet", "'analytics_events'!A:A");
            verify(context.getBean(AnalyticsEventRepository.class)).findPendingForSheet(500);
        });
    }

    @Test
    void usesSeoulAsDefaultZone() {
        enabledRunner().withPropertyValues(PREFIX + "sync-cron=0 0 9 * * *").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(next(task(context))).isEqualTo(Instant.parse("2026-10-13T00:00:00Z"));
        });
    }

    @Test
    void bindsCustomSettingsAndRegistersCustomCronAndZone() {
        enabledRunner().withPropertyValues(
                PREFIX + "spreadsheet-id=custom-spreadsheet", PREFIX + "sheet-name=custom-events",
                PREFIX + "sync-cron=0 30 8 * * *", PREFIX + "sync-zone=UTC",
                PREFIX + "batch-size=7", PREFIX + "max-batches-per-run=3").run(context -> {
                    assertThat(context).hasNotFailed();
                    AnalyticsSheetSyncProperties properties = context.getBean(AnalyticsSheetSyncProperties.class);
                    assertThat(properties.getSpreadsheetId()).isEqualTo("custom-spreadsheet");
                    assertThat(properties.getSheetName()).isEqualTo("custom-events");
                    assertThat(properties.batchSizeValue()).isEqualTo(7);
                    assertThat(properties.maxBatchesValue()).isEqualTo(3);
                    CronTask task = task(context);
                    assertThat(task.getExpression()).isEqualTo("0 30 8 * * *");
                    assertThat(next(task)).isEqualTo(Instant.parse("2026-10-12T08:30:00Z"));
                });
    }

    @ParameterizedTest
    @CsvSource({
            "sync-cron,not-a-cron", "sync-cron,''",
            "sync-zone,Not/AZone", "sync-zone,''",
            "batch-size,not-a-number", "batch-size,0", "batch-size,1001",
            "max-batches-per-run,not-a-number", "max-batches-per-run,0"
    })
    void invalidSettingsKeepContextRunningAndSkipScheduledWork(String key, String value, CapturedOutput output) {
        enabledRunner().withPropertyValues(PREFIX + key + "=" + value).run(context -> {
            assertThat(context).hasNotFailed();
            CronTask task = task(context);
            assertThat(next(task)).isEqualTo(Instant.parse("2026-10-12T00:10:00Z"));

            task.getRunnable().run();

            verifyNoInteractions(context.getBean(GoogleSheetsGateway.class),
                    context.getBean(AnalyticsEventRepository.class),
                    context.getBean(PlatformTransactionManager.class));
            assertThat(output.getAll()).contains("WARN", "type=", "written=0", "existing=0")
                    .doesNotContain(SECRET, "test-spreadsheet");
        });
    }

    private ApplicationContextRunner enabledRunner() {
        return runner.withUserConfiguration(Dependencies.class).withPropertyValues(
                "app.google-sheets.enabled=true", PREFIX + "enabled=true",
                PREFIX + "spreadsheet-id=test-spreadsheet", PREFIX + "user-key-secret=" + SECRET);
    }

    private static CronTask task(AssertableApplicationContext context) {
        var scheduled = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.iterator().next().getTask()).isInstanceOf(CronTask.class);
        return (CronTask) scheduled.iterator().next().getTask();
    }

    private static Instant next(CronTask task) {
        return task.getTrigger().nextExecution(new SimpleTriggerContext(CLOCK));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    @EnableScheduling
    @Import({AnalyticsSheetSyncService.class, AnalyticsSheetSyncScheduler.class})
    static class SchedulingConfiguration {
        @Bean
        AnalyticsSheetSyncProperties analyticsSheetSyncProperties() {
            return new AnalyticsSheetSyncProperties();
        }

        @Bean
        TaskScheduler taskScheduler() {
            // 예약을 등록만 하고 테스트 실행 시각에 따라 자동 실행되지는 않게 한다.
            return mock(TaskScheduler.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        GoogleSheetsGateway gateway() {
            return mock(GoogleSheetsGateway.class);
        }

        @Bean
        AnalyticsEventRepository repository() {
            return mock(AnalyticsEventRepository.class);
        }

        @Bean
        AnalyticsSheetRowMapper rowMapper() {
            return new AnalyticsSheetRowMapper(JsonMapper.builder().build());
        }

        @Bean
        Clock clock() {
            return CLOCK;
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }
}
