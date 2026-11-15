package com.security.interceptor;

import com.security.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Interceptor para logging de seguridad y validaciones adicionales
 */
@Component
public class SecurityInterceptor implements HandlerInterceptor {

    private static final Logger logger = Logger.getLogger(SecurityInterceptor.class.getName());

    @Autowired
    private SecurityUtils securityUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String requestURI = request.getRequestURI();
        String method = request.getMethod();
        String remoteAddr = getClientIpAddress(request);

        // Log básico para auditoría (solo en DEBUG)
        if (logger.isLoggable(java.util.logging.Level.FINE)) {
            logger.fine(String.format("Request - Method: %s, URI: %s, IP: %s",
                    method, (requestURI.startsWith("/api/auth/") || requestURI.startsWith("/api/2fa/")) ? "[auth]" : requestURI, remoteAddr));
        }

        // Saltar validaciones para OPTIONS (preflight CORS)
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        // Saltar validaciones para endpoints estáticos y de salud
        if (isSkippableEndpoint(requestURI)) {
            return true;
        }

        // Validación suave de parámetros (solo advertencias)
        validateRequestParametersSoft(request, remoteAddr);

        // Validación suave de headers (solo advertencias)
        validateRequestHeadersSoft(request, remoteAddr);

        // Siempre permitir el request (no bloqueamos)
        return true;
    }

    /**
     * Verifica si el endpoint debe saltar las validaciones de seguridad
     */
    private boolean isSkippableEndpoint(String requestURI) {
        return requestURI.startsWith("/actuator/") ||
                requestURI.startsWith("/css/") ||
                requestURI.startsWith("/js/") ||
                requestURI.startsWith("/images/") ||
                requestURI.startsWith("/favicon.ico") ||
                requestURI.startsWith("/error") ||
                requestURI.startsWith("/swagger-ui/") ||
                requestURI.startsWith("/v3/api-docs/");
    }

    /**
     * Validación suave de parámetros (solo logs de advertencia)
     */
    private void validateRequestParametersSoft(HttpServletRequest request, String remoteAddr) {
        try {
            Enumeration<String> parameterNames = request.getParameterNames();
            while (parameterNames.hasMoreElements()) {
                String paramName = parameterNames.nextElement();
                String[] paramValues = request.getParameterValues(paramName);

                if (paramValues != null) {
                    for (String paramValue : paramValues) {
                        if (paramValue != null && !securityUtils.isSafeString(paramValue)) {
                            // Solo advertencia, no bloqueamos
                            logger.warning("Potentially unsafe parameter detected from " + remoteAddr +
                                    ": " + paramName + " (logged for monitoring)");
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.fine("Error validating request parameters (non-critical): " + e.getMessage());
        }
    }

    /**
     * Validación suave de headers (solo logs de advertencia)
     */
    private void validateRequestHeadersSoft(HttpServletRequest request, String remoteAddr) {
        try {
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                String headerValue = request.getHeader(headerName);

                // Solo validar headers críticos, no todos
                if (isCriticalHeader(headerName) && headerValue != null &&
                        !securityUtils.isSafeString(headerValue)) {
                    // Solo advertencia, no bloqueamos
                    logger.warning("Potentially unsafe critical header detected from " + remoteAddr +
                            ": " + headerName + " (logged for monitoring)");
                }
            }
        } catch (Exception e) {
            logger.fine("Error validating request headers (non-critical): " + e.getMessage());
        }
    }

    /**
     * Verifica si un header es crítico para validación
     */
    private boolean isCriticalHeader(String headerName) {
        return headerName.equalsIgnoreCase("Authorization") ||
                headerName.equalsIgnoreCase("Content-Type") ||
                headerName.equalsIgnoreCase("X-Requested-With") ||
                headerName.toLowerCase().startsWith("x-custom-");
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedForHeader = request.getHeader("X-Forwarded-For");
        if (xForwardedForHeader != null && !xForwardedForHeader.isEmpty()) {
            return xForwardedForHeader.split(",")[0].trim();
        }

        String xRealIpHeader = request.getHeader("X-Real-IP");
        if (xRealIpHeader != null && !xRealIpHeader.isEmpty()) {
            return xRealIpHeader;
        }

        return request.getRemoteAddr();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) throws Exception {

        if (ex != null) {
            logger.severe("Request completed with exception: " + ex.getClass().getSimpleName() +
                    " for request method: " + request.getMethod() +
                    " from IP: " + getClientIpAddress(request));
        }
    }
}