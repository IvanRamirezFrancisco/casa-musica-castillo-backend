package com.security.controller;

import com.security.dto.request.LoginRequest;
import com.security.entity.User;
import com.security.security.*;
import com.security.service.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthLoginContractTest {
    @Mock AuthService auth;
    @Mock UserService users;
    @Mock LoginSecurityService limits;
    @Mock SessionManagementService sessions;
    @Mock JwtTokenProvider jwt;
    @Mock AuthenticationManager manager;
    @Mock LoginChallengeService challenges;
    AuthController controller;
    User user;
    AutoCloseable mocks;
    @BeforeEach void setup() {
        mocks=MockitoAnnotations.openMocks(this);controller=new AuthController();
        ReflectionTestUtils.setField(controller,"authService",auth);
        ReflectionTestUtils.setField(controller,"userService",users);
        ReflectionTestUtils.setField(controller,"loginSecurityService",limits);
        ReflectionTestUtils.setField(controller,"sessionManagementService",sessions);
        ReflectionTestUtils.setField(controller,"jwtTokenProvider",jwt);
        ReflectionTestUtils.setField(controller,"authenticationManager",manager);
        ReflectionTestUtils.setField(controller,"loginChallenges",challenges);
        user=new User();user.setId(1L);user.setEmail("synthetic@example.invalid");user.setEnabled(true);
        when(users.getUserByEmail(user.getEmail())).thenReturn(user);
        var principal=UserPrincipal.create(user);
        when(manager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        when(jwt.generateToken(any(),any())).thenReturn("synthetic-session-access");
    }
    @AfterEach void close() throws Exception {mocks.close();}
    LoginRequest request(){LoginRequest request=new LoginRequest();request.setEmail(user.getEmail());request.setPassword("SyntheticOnlyPassword!123");return request;}
    @Test void normalLoginUsesOfficialSessionGenerator() {
        assertEquals(200,controller.authenticateUser(request(),new MockHttpServletRequest()).getStatusCode().value());
        verify(jwt).generateToken(any(),any());verifyNoInteractions(challenges);
    }
    @Test void incorrectPasswordOrUnknownAccountNeverCreatesChallengeOrJwt() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("synthetic"));
        assertEquals(401,controller.authenticateUser(request(),new MockHttpServletRequest()).getStatusCode().value());
        verify(limits).recordFailedAttempt(user.getEmail());verifyNoInteractions(jwt,challenges);
    }
    @Test void disabledAccountCannotAuthenticate() {
        when(manager.authenticate(any())).thenThrow(new DisabledException("synthetic"));
        assertEquals(401,controller.authenticateUser(request(),new MockHttpServletRequest()).getStatusCode().value());
        verifyNoInteractions(jwt,challenges);
    }
    @Test void lockedAccountRateLimited() {
        when(limits.isAccountLocked(user.getEmail())).thenReturn(true);
        assertEquals(429,controller.authenticateUser(request(),new MockHttpServletRequest()).getStatusCode().value());
        verifyNoInteractions(manager,jwt,challenges);
    }
    @Test void passwordValidatedBeforeChallengeAndNoJwt() {
        user.setEmailEnabled(true);
        when(challenges.create(1L)).thenReturn(new LoginChallengeService.PendingLogin(true,"A".repeat(43),Map.of("EMAIL",true)));
        var response=controller.authenticateUser(request(),new MockHttpServletRequest());assertEquals(200,response.getStatusCode().value());
        var order=inOrder(manager,challenges);order.verify(manager).authenticate(any());order.verify(challenges).create(1L);
        verifyNoInteractions(jwt);
    }
    @Test void meUsesAuthenticatedIdentityWithoutReparsingToken() {
        controller.getCurrentUser(UserPrincipal.create(user));verify(users).getUserResponseById(1L);verifyNoInteractions(jwt);
    }
    @Test void logoutOnlyRevokesCurrentSession() {
        var request=new MockHttpServletRequest();request.setAttribute("authenticatedJti","current-synthetic-jti");
        controller.logout(request);verify(sessions).invalidateSession("current-synthetic-jti");verifyNoMoreInteractions(sessions);
    }
    @Test void logoutAllUsesAuthenticationAndSessionsEnforceOwnership() {
        var principal=UserPrincipal.create(user);var authentication=new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities());
        controller.logoutFromAllDevices(authentication);verify(sessions).invalidateAllUserSessions(user.getEmail());
        assertEquals(404,controller.closeSpecificSession("foreign",authentication).getStatusCode().value());
        verify(sessions).revokeOwnedSession("foreign",user.getEmail());verify(sessions,never()).invalidateSession("foreign");
    }
    @Test void failedAttemptThatLocksIpImmediatelyReturns429() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("synthetic"));
        when(limits.isAccountLocked("127.0.0.1")).thenReturn(false,true);
        var request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");
        var result=controller.authenticateUser(request(),request);
        assertEquals(429,result.getStatusCode().value());
        verify(limits).recordFailedAttempt("127.0.0.1");verifyNoInteractions(jwt,challenges);
    }

}
