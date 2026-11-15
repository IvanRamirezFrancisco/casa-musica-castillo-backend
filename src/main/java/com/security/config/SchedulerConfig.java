package com.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Configuración del {@link ThreadPoolTaskScheduler} usado por el motor
 * de automatizaciones dinámicas
 * ({@link com.security.service.DynamicSchedulerService}).
 *
 * <p>
 * <strong>Separación de concerns:</strong> este pool es independiente del
 * {@code backupTaskExecutor} de {@link AsyncConfig}. Los hilos de este
 * scheduler solo evalúan los triggers cron y despachan las tareas; la
 * ejecución pesada (pg_dump, VACUUM, etc.) se delega al pool async
 * existente.
 * </p>
 *
 * <p>
 * Pool size = 5 permite hasta 5 cron jobs disparando simultáneamente
 * sin bloquear al resto.
 * </p>
 */
@Configuration
public class SchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfig.class);

    @Bean(name = "automationTaskScheduler")
    public ThreadPoolTaskScheduler automationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("auto-job-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setErrorHandler(
                t -> log.error("❌ [AutomationScheduler] Error no capturado en job: {}", t.getMessage(), t));
        scheduler.initialize();
        log.info("✅ [SchedulerConfig] ThreadPoolTaskScheduler 'automationTaskScheduler' inicializado (poolSize=5)");
        return scheduler;
    }
}
