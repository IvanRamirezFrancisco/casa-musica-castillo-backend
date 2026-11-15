package com.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MailConfigDebug {

    private static final Logger log = LoggerFactory.getLogger(MailConfigDebug.class);

    @Value("${spring.mail.host}")
    private String mailHost;

    @Value("${spring.mail.port}")
    private String mailPort;

    @Value("${spring.mail.username}")
    private String mailUsername;

    @Bean
    public ApplicationRunner debugMailConfig() {
        return args -> {
            log.debug("Mail config — host={}, port={}, username=[masked]", mailHost, mailPort);
        };
    }
}