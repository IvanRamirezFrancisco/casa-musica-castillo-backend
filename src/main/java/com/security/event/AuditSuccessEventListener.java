package com.security.event;

import com.security.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listener independiente para eventos de auditoría exitosos después del commit.
 * Al estar en un bean separado, garantizamos que la llamada al AuditLogService
 * atraviese su proxy Spring, respetando así sus anotaciones @Async y @Transactional.
 */
@Component
public class AuditSuccessEventListener {

    private static final Logger logger = LoggerFactory.getLogger(AuditSuccessEventListener.class);
    
    private final AuditLogService auditLogService;

    public AuditSuccessEventListener(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAuditSuccessEvent(AuditSuccessEvent event) {
        logger.debug("Recibido evento de auditoría exitoso AFTER_COMMIT: {} -> {}", event.getEntityType(), event.getAction());
        // Se llama al bean AuditLogService a través del proxy, activando @Async y @Transactional
        auditLogService.log(
                event.getAction(), 
                event.getEventType(), 
                event.getEntityType(),
                event.getEntityId(), 
                event.getOldValues(), 
                event.getNewValues(),
                event.getSeverity(), 
                true
        );
    }
}
