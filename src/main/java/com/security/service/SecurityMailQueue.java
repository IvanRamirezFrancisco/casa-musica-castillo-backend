package com.security.service;

import com.security.entity.User;
import com.security.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.LoggerFactory;

/** Password mail intents reuse the activation crypto, limiter and one-attempt transport.
 * No network request is performed in a database transaction. UNKNOWN is never replayed. */
@Service
public class SecurityMailQueue {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final EmailService mail;
    private final ActivationCrypto crypto;
    private final ObjectMapper json;
    private final Clock clock;
    private final VerificationRequestLimit limits;
    private final TransactionTemplate tx;
    public SecurityMailQueue(JdbcTemplate jdbc, UserRepository users, EmailService mail, ActivationCrypto crypto,
            ObjectMapper json, Clock clock, PlatformTransactionManager manager, VerificationRequestLimit limits) {
        this.jdbc=jdbc;this.users=users;this.mail=mail;this.crypto=crypto;this.json=json;this.clock=clock;
        this.limits=limits;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueueReset(User user,long tokenId,String token,LocalDateTime expires) {
        enqueue(user,"PASSWORD_RESET",tokenId,token,expires);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueueNotification(User user) {
        enqueue(user,"PASSWORD_CHANGED",null,"",LocalDateTime.now(clock).plusDays(1));
    }
    private void enqueue(User user,String purpose,Long tokenId,String token,LocalDateTime expires) {
        String operation=UUID.randomUUID().toString();
        try {
            String payload=json.writeValueAsString(Map.of("email",user.getEmail(),
                "name",user.getFirstName()==null?"":user.getFirstName(),"token",token));
            jdbc.update("""
                INSERT INTO auth.security_mail_outbox(operation_id,purpose,user_id,reset_token_id,state,payload,created_at,expires_at)
                VALUES (?,?,?,?,'PENDING',?,?,?)
                """,operation,purpose,user.getId(),tokenId,crypto.seal(payload,"password-mail:"+operation),LocalDateTime.now(clock),expires);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot prepare security notification");
        }
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void dispatchPending() {
        // Expired intents cannot block later notifications when a recipient budget is exhausted.
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE auth.security_mail_outbox SET state='CANCELLED',payload=NULL,completed_at=? WHERE id IN (SELECT id FROM auth.security_mail_outbox WHERE state='PENDING' AND expires_at<=? LIMIT 1000)",
                LocalDateTime.now(clock),LocalDateTime.now(clock));
        });
        for (int i = 0; i < 10; i++) {
            final long[] claimedId = { -1L };
            tx.executeWithoutResult(status -> {
                var ids = jdbc.queryForList("SELECT id FROM auth.security_mail_outbox WHERE state='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", Long.class, LocalDateTime.now(clock));
                if (ids.isEmpty()) {
                    claimedId[0] = -1L;
                    return;
                }
                long item = ids.getFirst();
                var pending = jdbc.queryForMap("SELECT purpose,operation_id,payload FROM auth.security_mail_outbox WHERE id=?", item);
                if ("PASSWORD_CHANGED".equals(pending.get("purpose"))) {
                    String recipient = null;
                    try { recipient = (String) json.readValue(crypto.open((String) pending.get("payload"),
                        "password-mail:" + pending.get("operation_id")), Map.class).get("email"); }
                    catch (Exception ex) { /* Claim then record an unrecoverable payload safely below. */ }
                    if (recipient != null && !limits.reserveDeliveryFor("notification-recipient-day", recipient.toLowerCase(Locale.ROOT))) {
                        // Defer only this unsent intent; other recipients must keep making progress.
                        jdbc.update("UPDATE auth.security_mail_outbox SET next_attempt_at=? WHERE id=?", LocalDateTime.now(clock).plusMinutes(1), item);
                        claimedId[0] = 0L;
                        return;
                    }
                }
                jdbc.update("UPDATE auth.security_mail_outbox SET state='SENDING',started_at=? WHERE id=?", LocalDateTime.now(clock), item);
                jdbc.update("UPDATE security.password_reset_tokens SET delivery_state='SENDING' WHERE id=(SELECT reset_token_id FROM auth.security_mail_outbox WHERE id=?) AND used=false", item);
                claimedId[0] = item;
            });
            long id = claimedId[0];
            if (id < 0L) break;
            if (id == 0L) continue;
            dispatch(id);
        }
        tx.executeWithoutResult(status->{
            jdbc.update("""
                UPDATE security.password_reset_tokens SET delivery_state='UNKNOWN'
                WHERE used=false AND id IN (SELECT reset_token_id FROM auth.security_mail_outbox
                  WHERE state='SENDING' AND started_at < ?)
                """,LocalDateTime.now(clock).minusMinutes(5));
            jdbc.update("UPDATE auth.security_mail_outbox SET state='UNKNOWN',payload=NULL,completed_at=? WHERE state='SENDING' AND started_at < ?",
                LocalDateTime.now(clock),LocalDateTime.now(clock).minusMinutes(5));
        });
    }
    private void dispatch(long id) {
        Map<String,Object> row=tx.execute(status->jdbc.queryForMap("SELECT * FROM auth.security_mail_outbox WHERE id=?",id));
        EmailService.ActivationDelivery outcome=EmailService.ActivationDelivery.FAILED;
        boolean eligible=false;
        try {
            Map<?,?> data=json.readValue(crypto.open((String)row.get("payload"),"password-mail:"+row.get("operation_id")),Map.class);
            String email=(String)data.get("email");
            eligible=tx.execute(status->{
                var user=users.findByIdForAuthUpdate(((Number)row.get("user_id")).longValue()).orElse(null);
                if(user==null || !((java.sql.Timestamp)row.get("expires_at")).toLocalDateTime().isAfter(LocalDateTime.now(clock)))return false;
                if("PASSWORD_CHANGED".equals(row.get("purpose")))return true;
                Boolean used=jdbc.queryForObject("SELECT used FROM security.password_reset_tokens WHERE id=?",Boolean.class,row.get("reset_token_id"));
                return !Boolean.TRUE.equals(used) && Boolean.TRUE.equals(user.getEnabled()) &&
                    Boolean.TRUE.equals(user.getAccountNonLocked()) && email.equalsIgnoreCase(user.getEmail());
            });
            if(eligible) {
                User recipient=new User();recipient.setEmail(email);recipient.setFirstName((String)data.get("name"));
                outcome="PASSWORD_RESET".equals(row.get("purpose"))
                    ? mail.deliverPasswordReset(recipient,(String)data.get("token"))
                    : mail.deliverPasswordChanged(recipient);
            }
        } catch (Exception ex) {
            if(eligible)outcome=EmailService.ActivationDelivery.UNKNOWN;
            LoggerFactory.getLogger(getClass()).warn("security_mail_preparation_failed operation={}",id);
        }
        EmailService.ActivationDelivery result=outcome;
        boolean allowed=eligible;
        tx.executeWithoutResult(status->{
            User user=users.findByIdForAuthUpdate(((Number)row.get("user_id")).longValue()).orElse(null);
            Long tokenId=row.get("reset_token_id")==null?null:((Number)row.get("reset_token_id")).longValue();
            if(tokenId!=null) {
                boolean accountValid=user!=null && Boolean.TRUE.equals(user.getEnabled()) && Boolean.TRUE.equals(user.getAccountNonLocked());
                if(allowed && accountValid && result==EmailService.ActivationDelivery.ACCEPTED)
                    jdbc.update("UPDATE security.password_reset_tokens SET used=true,used_at=? WHERE user_id=? AND id < ?",LocalDateTime.now(clock),user.getId(),tokenId);
                jdbc.update("""
                    UPDATE security.password_reset_tokens SET delivery_state=?,
                    used=CASE WHEN ? OR ? THEN true ELSE used END
                    WHERE id=?
                    """,result.name(),!accountValid,result==EmailService.ActivationDelivery.FAILED,tokenId);
            }
            jdbc.update("UPDATE auth.security_mail_outbox SET state=?,payload=NULL,completed_at=? WHERE id=?",
                result.name(),LocalDateTime.now(clock),id);
            LoggerFactory.getLogger(getClass()).info("security_mail operation={} purpose={} outcome={}",id,row.get("purpose"),result);
        });
    }
    public void cleanup() {
        jdbc.update("""
            DELETE FROM auth.security_mail_outbox WHERE id IN
            (SELECT id FROM auth.security_mail_outbox WHERE expires_at < ? AND state <> 'SENDING' LIMIT 10000)
            """,LocalDateTime.now(clock).minusDays(1));
    }
}
