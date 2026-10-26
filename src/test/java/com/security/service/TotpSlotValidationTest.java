package com.security.service;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;

class TotpSlotValidationTest {
    @Test void preservesFiveSlotToleranceAndReturnsMatchingCounter() {
        long currentSlot=50_000_000L;
        var service=new TotpService(Clock.fixed(Instant.ofEpochMilli(currentSlot*30_000L),ZoneOffset.UTC));
        var generator=new GoogleAuthenticator();
        String secret;
        java.util.Set<Integer> codes;
        do {
            secret=generator.createCredentials().getKey();codes=new java.util.HashSet<>();
            for(int offset=-3;offset<=3;offset++) codes.add(generator.getTotpPassword(secret,(currentSlot+offset)*30_000L));
        } while(codes.size()!=7);
        for(int offset=-2;offset<=2;offset++) {
            long slot=currentSlot+offset;
            String code=String.format(java.util.Locale.ROOT,"%06d",generator.getTotpPassword(secret,slot*30_000L));
            assertEquals(slot,service.findMatchingSlot(secret,code).orElseThrow());
            assertTrue(service.verifyCode(secret,code));
        }
        for(int offset:new int[]{-3,3}) {
            String code=String.format(java.util.Locale.ROOT,"%06d",generator.getTotpPassword(secret,(currentSlot+offset)*30_000L));
            assertTrue(service.findMatchingSlot(secret,code).isEmpty());
        }
        assertTrue(service.findMatchingSlot(secret,"invalid").isEmpty());
        assertTrue(service.findMatchingSlot(null,"123456").isEmpty());
    }
}
