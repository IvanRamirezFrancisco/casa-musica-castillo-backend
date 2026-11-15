package com.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * Configura el ThreadPool dedicado para las tareas {@code @Async} del sistema.
 *
 * <h2>Por qué un pool separado para backups?</h2>
 * <p>
 * Si usáramos el pool {@code @Async} global de Spring, una rafaga de peticiones
 * de backup podría saturar los hilos disponibles para otras tareas asíncronas
 * (emails, notificaciones). Al nombrarlo {@code "backupTaskExecutor"}, las
 * tareas de backup usan su propio conjunto de hilos sin interferir con el
 * resto.
 * </p>
 *
 * <h2>Parámetros del pool</h2>
 * <ul>
 * <li><strong>corePoolSize = 1</strong>: normalmente solo un backup corre a la
 * vez.</li>
 * <li><strong>maxPoolSize = 2</strong>: permite hasta 2 backups simultáneos en
 * picos.</li>
 * <li><strong>queueCapacity = 5</strong>: hasta 5 solicitudes esperando sin
 * rechazar.</li>
 * <li><strong>keepAliveSeconds = 60</strong>: hilos extra se destruyen tras 60
 * s de inactividad.</li>
 * </ul>
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Executor nombrado para las tareas de backup.
     * Se inyecta por nombre en {@code @Async("backupTaskExecutor")}.
     */
    @Bean(name = "backupTaskExecutor")
    public Executor backupTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // instancia un nuevo executor de pool de hilos, usara este hilo para ejecutar
        // los backups
        executor.setCorePoolSize(1);

        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(5);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("backup-"); // Facilita la lectura en logs: "backup-1"
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120); // Esperar hasta 2 min al apagar el servidor
        executor.initialize();
        log.info("✅ [AsyncConfig] Pool 'backupTaskExecutor' inicializado (core=1, max=3, queue=5)");
        return executor;
    }

    /**
     * Manejador global de excepciones no capturadas en métodos @Async.
     * Sin esto, las excepciones se tragan silenciosamente en hilos sin retorno
     * (void).
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new BackupAsyncExceptionHandler();
    }

    // ── Handler interno ───────────────────────────────────────────────────────

    private static class BackupAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        private static final Logger handlerLog = LoggerFactory.getLogger(BackupAsyncExceptionHandler.class);

        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            handlerLog.error(
                    "❌ [Async] Excepción no capturada en método '{}' con parámetros {}: {}",
                    method.getName(), Arrays.toString(params), ex.getMessage(), ex);
        }
    }
}
