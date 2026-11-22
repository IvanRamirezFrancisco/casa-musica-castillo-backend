package com.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "app.security.google")
public class GoogleAuthProperties {
    private boolean enabled = true;
    private String webClientId = "";
    private List<String> allowedClientIds = new ArrayList<>();
    private String jwkSetUri = "https://www.googleapis.com/oauth2/v3/certs";
    private long clockSkewSeconds = 60;
    private long challengeTtlSeconds = 300;
    private long registrationTtlSeconds = 600;
    private long reauthTtlSeconds = 300;
    private long linkConfirmTtlSeconds = 300;
    private boolean allowLegacyUnauthenticatedChallenge = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getWebClientId() { return webClientId; }
    public void setWebClientId(String webClientId) { this.webClientId = webClientId == null ? "" : webClientId.trim(); }

    public List<String> getAllowedClientIds() { return allowedClientIds; }
    public void setAllowedClientIds(List<String> allowedClientIds) {
        this.allowedClientIds = allowedClientIds == null ? new ArrayList<>() : allowedClientIds;
    }

    public String getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }

    public long getClockSkewSeconds() { return clockSkewSeconds; }
    public void setClockSkewSeconds(long clockSkewSeconds) { this.clockSkewSeconds = clockSkewSeconds; }

    public long getChallengeTtlSeconds() { return challengeTtlSeconds; }
    public void setChallengeTtlSeconds(long challengeTtlSeconds) { this.challengeTtlSeconds = challengeTtlSeconds; }

    public long getRegistrationTtlSeconds() { return registrationTtlSeconds; }
    public void setRegistrationTtlSeconds(long registrationTtlSeconds) { this.registrationTtlSeconds = registrationTtlSeconds; }

    public long getReauthTtlSeconds() { return reauthTtlSeconds; }
    public void setReauthTtlSeconds(long reauthTtlSeconds) { this.reauthTtlSeconds = reauthTtlSeconds; }

    public long getLinkConfirmTtlSeconds() { return linkConfirmTtlSeconds; }
    public void setLinkConfirmTtlSeconds(long linkConfirmTtlSeconds) { this.linkConfirmTtlSeconds = linkConfirmTtlSeconds; }

    public boolean isAllowLegacyUnauthenticatedChallenge() { return allowLegacyUnauthenticatedChallenge; }
    public void setAllowLegacyUnauthenticatedChallenge(boolean allowLegacyUnauthenticatedChallenge) {
        this.allowLegacyUnauthenticatedChallenge = allowLegacyUnauthenticatedChallenge;
    }

    public Set<String> getEffectiveAllowedClientIds() {
        Set<String> clients = new LinkedHashSet<>();
        if (webClientId != null && !webClientId.isBlank()) {
            clients.add(webClientId.trim());
        }
        if (allowedClientIds != null) {
            for (String candidate : allowedClientIds) {
                if (candidate != null && !candidate.isBlank()) {
                    for (String split : candidate.split(",")) {
                        String trimmed = split.trim();
                        if (!trimmed.isEmpty()) clients.add(trimmed);
                    }
                }
            }
        }
        return Set.copyOf(clients);
    }

    public boolean isConfigured() {
        return enabled && webClientId != null && !webClientId.isBlank() && !getEffectiveAllowedClientIds().isEmpty();
    }
}
