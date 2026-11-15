package com.security.service.automation;

import com.security.entity.SystemAutomation;
import com.security.repository.AutomationExecutionLogRepository;
import com.security.repository.SystemAutomationRepository;
import com.security.repository.UserRepository;
import com.security.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DynamicSchedulerServiceTest {
    private static final String BACKUP = "0 0 3 * * *";
    private static final String INVENTORY = "0 0 4 * * *";
    private static final String SESSIONS = "0 0 5 * * *";
    private static final String ACCOUNTS = "0 0 6 * * *";

    @Test
    void defaultsPreserveAllEnabledJobs() {
        assertSchedules(Map.of(), BACKUP, INVENTORY, SESSIONS, ACCOUNTS);
    }

    @Test
    void bothFlagsOverrideEnabledDatabaseRecords() {
        assertSchedules(Map.of("APP_JOBS_BACKUP_DATABASE_ENABLED", "false",
                "APP_JOBS_INVENTORY_AUDIT_ENABLED", "false"), SESSIONS, ACCOUNTS);
    }

    @Test
    void flagsAreIndependent() {
        assertSchedules(Map.of("APP_JOBS_BACKUP_DATABASE_ENABLED", "false",
                "APP_JOBS_INVENTORY_AUDIT_ENABLED", "true"), INVENTORY, SESSIONS, ACCOUNTS);
        assertSchedules(Map.of("APP_JOBS_BACKUP_DATABASE_ENABLED", "true",
                "APP_JOBS_INVENTORY_AUDIT_ENABLED", "false"), BACKUP, SESSIONS, ACCOUNTS);
    }

    private void assertSchedules(Map<String, Object> env, String... expected) {
        var repository = mock(SystemAutomationRepository.class);
        var scheduler = mock(ThreadPoolTaskScheduler.class);
        var factory = mock(JobRunnableFactory.class);
        var executionLog = mock(AutomationExecutionLogRepository.class);
        List<String> scheduled = new ArrayList<>();
        when(repository.findByEnabledTrue()).thenReturn(List.of(
                job("BACKUP_DATABASE_JOB", BACKUP), job("INVENTORY_AUDIT_JOB", INVENTORY),
                job("SESSION_CLEANUP_JOB", SESSIONS), job("ACCOUNT_CLEANUP_JOB", ACCOUNTS),
                job("DB_MAINTENANCE_JOB", "0 0 7 * * *")));
        when(scheduler.schedule(any(Runnable.class), any(Trigger.class))).thenAnswer(call -> {
            scheduled.add(call.<CronTrigger>getArgument(1).getExpression());
            return mock(ScheduledFuture.class);
        });

        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .replace("systemEnvironment", new SystemEnvironmentPropertySource("systemEnvironment", env)))
                .withBean(SystemAutomationRepository.class, () -> repository)
                .withBean(AutomationExecutionLogRepository.class, () -> executionLog)
                .withBean("automationTaskScheduler", ThreadPoolTaskScheduler.class, () -> scheduler)
                .withBean(JobRunnableFactory.class, () -> factory)
                .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
                .withBean(RestTemplate.class, RestTemplate::new)
                .withBean(EmailService.class, () -> mock(EmailService.class))
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withUserConfiguration(DynamicSchedulerService.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DynamicSchedulerService.class);
                    assertThat(scheduled).containsExactly(expected);
                    verifyNoInteractions(factory, executionLog);
                });
    }

    private SystemAutomation job(String name, String cron) {
        var job = new SystemAutomation();
        job.setJobName(name);
        job.setCronExpression(cron);
        job.setEnabled(true);
        return job;
    }
}
