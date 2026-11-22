package com.security.service;

import com.security.entity.User;
import com.security.repository.UserRepository;
import com.security.repository.PasswordResetTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Compatibility adapter: legacy HTTP contracts use the same recovery implementation. */
@Service
public class PasswordResetService {
    private final SecurePasswordResetService recovery;
    private final PasswordResetTokenRepository tokens;
    private final UserRepository users;
    public PasswordResetService(SecurePasswordResetService recovery,PasswordResetTokenRepository tokens,UserRepository users) {
        this.recovery=recovery;this.tokens=tokens;this.users=users;
    }
    public void requestPasswordReset(String email,HttpServletRequest request) { recovery.requestPasswordReset(email,request); }
    public boolean validateResetToken(String token) { return recovery.isTokenValid(token); }
    public boolean resetPassword(String token,String password) { return recovery.resetPassword(token,password,currentRequest()); }
    private HttpServletRequest currentRequest() {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return attributes instanceof org.springframework.web.context.request.ServletRequestAttributes request ? request.getRequest() : null;
    }
    @Transactional(readOnly=true)
    public Optional<User> getUserByToken(String token) {
        if(!recovery.isTokenValid(token))return Optional.empty();
        return tokens.findByToken(SecurePasswordResetService.resetDigest(token)).or(()->tokens.findByToken(token))
            .flatMap(row->users.findById(row.getUser().getId()));
    }
    public void cleanupExpiredTokens() { tokens.deleteExpiredTokens(LocalDateTime.now()); }
    @Transactional(readOnly=true)
    public long getActiveTokensCount(String email) {
        return users.findByEmail(email).map(user->tokens.findByUserAndUsed(user,false).stream()
            .filter(row->row.getExpiryDate().isAfter(LocalDateTime.now())).count()).orElse(0L);
    }
    public void cleanupOldRecoveryAttempts() { recovery.cleanupOldRecords(); }
}
