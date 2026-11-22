package com.security.config;

import com.security.service.*;
import com.security.service.admin.StaffInvitationService;
import com.security.service.automation.DynamicSchedulerService;
import com.security.job.OrderExpirationJob;
import com.security.repository.PasswordRecoveryAttemptRepository;
import com.security.repository.PasswordResetTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalMaintenancePolicyTest {
    private <T> T paused(Class<T> type) throws Exception {
        // Mocks fail if an unguarded real cleanup touches its uninitialized repositories.
        T target=mock(type,CALLS_REAL_METHODS);
        var field=type.getDeclaredField("maintenanceEnabled");
        assertEquals("${app.maintenance.enabled:true}",field.getAnnotation(Value.class).value());
        ReflectionTestUtils.setField(target,"maintenanceEnabled",false);
        return target;
    }
    @Test void localMaintenancePauseProtectsHistoricalRowsAndDoesNotStartDynamicJobs() throws Exception {
        assertDoesNotThrow(()->paused(SessionManagementService.class).cleanupExpiredSessions());
        assertDoesNotThrow(()->paused(OrderExpirationJob.class).cancelExpiredOrders());
        assertDoesNotThrow(()->paused(DynamicSchedulerService.class).onStartup());
        assertDoesNotThrow(()->paused(ActivationJobs.class).cleanup());
        assertDoesNotThrow(()->paused(LoginChallengeService.class).cleanup());
        var recovery=paused(SecurePasswordResetService.class);
        var attempts=mock(PasswordRecoveryAttemptRepository.class);var tokens=mock(PasswordResetTokenRepository.class);
        ReflectionTestUtils.setField(recovery,"recoveryAttemptRepository",attempts);
        ReflectionTestUtils.setField(recovery,"passwordResetTokenRepository",tokens);
        recovery.cleanupOldRecords();verifyNoInteractions(attempts,tokens);
        assertDoesNotThrow(()->paused(StaffInvitationService.class).expireOldInvitations());
        assertDoesNotThrow(()->paused(DatabaseBackupService.class).recoverZombiePendingBackups());
    }
    @Test void activationAndPasswordDeliveryRemainAvailableWhileMaintenanceIsPaused() {
        var verification=mock(VerificationService.class);var limits=mock(VerificationRequestLimit.class);var queue=mock(SecurityMailQueue.class);
        var jobs=new ActivationJobs(verification,limits,queue);ReflectionTestUtils.setField(jobs,"maintenanceEnabled",false);
        jobs.dispatch();verify(verification).dispatchPending();verify(queue).dispatchPending();verifyNoInteractions(limits);
    }
}
