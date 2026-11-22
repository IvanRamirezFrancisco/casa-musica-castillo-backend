package com.security.config;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
@Configuration
public class ActivationClockConfiguration {
    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock activationClock() { return Clock.systemUTC(); }
}
