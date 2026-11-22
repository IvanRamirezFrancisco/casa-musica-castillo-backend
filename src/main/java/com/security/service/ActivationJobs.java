package com.security.service;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

@Component
@ConditionalOnProperty(name = "app.activation.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class ActivationJobs {
    @org.springframework.beans.factory.annotation.Value("${app.maintenance.enabled:true}")
    private boolean maintenanceEnabled = true;
    private final VerificationService verification;
    private final VerificationRequestLimit limits;
    private final SecurityMailQueue passwordMail;
    public ActivationJobs(VerificationService verification, VerificationRequestLimit limits, SecurityMailQueue passwordMail) {
        this.verification = verification; this.limits = limits;this.passwordMail=passwordMail;
    }
    @Scheduled(fixedDelayString = "${app.activation.dispatch-ms:1000}")
    public void dispatch() { verification.dispatchPending();passwordMail.dispatchPending(); }
    @Scheduled(fixedDelayString = "${app.activation.cleanup-ms:3600000}")
    public void cleanup() { if (!maintenanceEnabled) return; limits.cleanup(); verification.cleanupExpiredTokens();passwordMail.cleanup(); }
}
