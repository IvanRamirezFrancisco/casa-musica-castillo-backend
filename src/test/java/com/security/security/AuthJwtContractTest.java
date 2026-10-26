package com.security.security;

import com.security.entity.User;
import com.security.service.SessionManagementService;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthJwtContractTest {
    final String syntheticKey = "SyntheticOnlyAuthHardeningTestKey_".repeat(3);
    JwtTokenProvider jwt;
    SessionManagementService sessions;
    CustomUserDetailsService details;
    JwtAuthenticationFilter filter;
    User user;
    @BeforeEach void setup() {
        sessions=mock(SessionManagementService.class); details=mock(CustomUserDetailsService.class);
        jwt=new JwtTokenProvider();
        ReflectionTestUtils.setField(jwt,"jwtSecret",syntheticKey);
        ReflectionTestUtils.setField(jwt,"jwtExpirationInMs",900000);
        ReflectionTestUtils.setField(jwt,"sessionManagementService",sessions);jwt.init();
        filter=new JwtAuthenticationFilter();
        ReflectionTestUtils.setField(filter,"tokenProvider",jwt);
        ReflectionTestUtils.setField(filter,"customUserDetailsService",details);
        ReflectionTestUtils.setField(filter,"sessionManagementService",sessions);
        user=new User();user.setId(1L);user.setEmail("synthetic@example.invalid");user.setEnabled(true);
        when(details.loadUserById(1L)).thenAnswer(i->UserPrincipal.create(user));
        when(sessions.isSessionValid("synthetic-jti")).thenReturn(true);
        when(sessions.belongsToUser("synthetic-jti",1L)).thenReturn(true);
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    String token(String jti, long expiry, String key, SignatureAlgorithm algorithm) {
        return Jwts.builder().setSubject("1").setId(jti).setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis()+expiry))
                .signWith(Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8)),algorithm).compact();
    }
    void filter(String token) throws Exception {
        var request=new MockHttpServletRequest("GET","/api/auth/me");
        if(token!=null)request.addHeader("Authorization","Bearer "+token);
        filter.doFilter(request,new MockHttpServletResponse(),new MockFilterChain());
    }
    @Test void officialGeneratorUsesHs512AndPersistentJti() {
        when(sessions.createSession(any(),any(),any())).thenReturn("synthetic-jti");
        var principal=UserPrincipal.create(user);
        String token=jwt.generateToken(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()),new MockHttpServletRequest());
        var parsed=Jwts.parserBuilder().setSigningKey(Keys.hmacShaKeyFor(syntheticKey.getBytes(StandardCharsets.UTF_8))).build().parseClaimsJws(token);
        assertEquals("HS512",parsed.getHeader().getAlgorithm());
        assertEquals("1",parsed.getBody().getSubject());assertEquals("synthetic-jti",parsed.getBody().getId());
        assertNotNull(parsed.getBody().get("roles"));assertNotNull(parsed.getBody().getIssuedAt());
        assertEquals(user.getEmail(),parsed.getBody().get("email"));assertTrue(jwt.validateToken(token));
    }
    @Test void meAuthenticatesValidJwtWithCurrentUser() throws Exception {
        filter(token("synthetic-jti",10000,syntheticKey,SignatureAlgorithm.HS512));
        assertEquals(user.getEmail(),SecurityContextHolder.getContext().getAuthentication().getName());
        verify(sessions).updateSessionActivity("synthetic-jti");
    }
    @Test void invalidExpiredMissingJtiAndWrongAlgorithmFailClosed() throws Exception {
        for(String token:new String[]{null,"malformed", token(null,10000,syntheticKey,SignatureAlgorithm.HS512),
                token("synthetic-jti",-10000,syntheticKey,SignatureAlgorithm.HS512),
                token("synthetic-jti",10000,"DifferentSyntheticOnlyTestKey_".repeat(3),SignatureAlgorithm.HS512),
                token("synthetic-jti",10000,syntheticKey,SignatureAlgorithm.HS256)}) {
            filter(token);assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }
    @ParameterizedTest @ValueSource(strings={"missing","revoked","inactive","expired"})
    void invalidSessionRejected(String reason) throws Exception {
        when(sessions.isSessionValid("synthetic-jti")).thenReturn(false);
        filter(token("synthetic-jti",10000,syntheticKey,SignatureAlgorithm.HS512));
        assertNull(SecurityContextHolder.getContext().getAuthentication());verifyNoInteractions(details);
    }
    @Test void disabledUserAndMismatchedOwnershipFailClosed() throws Exception {
        user.setEnabled(false);filter(token("synthetic-jti",10000,syntheticKey,SignatureAlgorithm.HS512));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        user.setEnabled(true);when(sessions.belongsToUser(any(),any())).thenReturn(false);
        filter(token("synthetic-jti",10000,syntheticKey,SignatureAlgorithm.HS512));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void publicRoutesAreExactAndProtectedAuthIsFiltered() {
        for(String path:PublicAuthRoutes.EXACT)assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("POST",path)),path);
        for(String path:new String[]{"/api/auth/me","/api/auth/logout","/api/auth/logout-all","/api/auth/sessions","/api/auth/sessions/other","/api/2fa/verify-extra"})
            assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("POST",path)),path);
    }
    @Test void missingOrShortSecretFailsAtStartup() {
        ReflectionTestUtils.setField(jwt,"jwtSecret","");assertThrows(IllegalStateException.class,jwt::init);
        ReflectionTestUtils.setField(jwt,"jwtSecret",null);assertThrows(IllegalStateException.class,jwt::init);
    }
}
