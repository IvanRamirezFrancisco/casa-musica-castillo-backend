package com.security.config;

import com.security.security.CustomUserDetailsService;
import com.security.security.JwtAuthenticationEntryPoint;
import com.security.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

        // FASE 0 - Seguridad - 2026-05-15
        // Inyectado para detectar el perfil activo y aplicar reglas diferenciadas
        // en endpoints sensibles (Swagger, H2, TestController).
        @Autowired
        private Environment environment;

        @Autowired
        private CustomUserDetailsService customUserDetailsService;

        @Autowired
        private JwtAuthenticationEntryPoint unauthorizedHandler;

        @Bean
        public JwtAuthenticationFilter jwtAuthenticationFilter() {
                return new JwtAuthenticationFilter();
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder(12);
        }

        // Fuente única de CORS. En producción proviene de CORS_ORIGINS (obligatoria).
        @Value("${cors.allowed-origins}")
        private List<String> allowedOrigins;

        @Bean
        public DaoAuthenticationProvider authenticationProvider() {
                DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
                authProvider.setUserDetailsService(customUserDetailsService);
                authProvider.setPasswordEncoder(passwordEncoder());
                return authProvider;
        }

        @Bean
        public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
                return authConfig.getAuthenticationManager();
        }

        /**
         * Configuración CORS: única fuente de verdad (cors.allowed-origins ← CORS_ORIGINS).
         */
        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration config = new CorsConfiguration();

                // Normalizar: trim, quitar '/' final y descartar vacíos
                List<String> origins = allowedOrigins.stream()
                                .map(String::trim)
                                .map(o -> o.endsWith("/") ? o.substring(0, o.length() - 1) : o)
                                .filter(o -> !o.isEmpty())
                                .toList();
                if (origins.isEmpty()) {
                        throw new IllegalStateException(
                                        "CORS_ORIGINS (cors.allowed-origins) está vacío: define una lista explícita de orígenes separada por comas.");
                }
                if (origins.contains("*")) {
                        throw new IllegalStateException(
                                        "CORS_ORIGINS no puede contener '*' porque allowCredentials=true; usa orígenes explícitos.");
                }

                // IMPORTANTE: Usar SOLO allowedOriginPatterns cuando allowCredentials es true
                // NO usar setAllowedOrigins con "*" porque causa error
                config.setAllowedOriginPatterns(origins);

                // Métodos HTTP permitidos
                config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));

                // Headers permitidos - solo los necesarios
                config.setAllowedHeaders(Arrays.asList(
                                "Authorization", "Content-Type", "Accept", "Origin",
                                "X-Requested-With", "Cache-Control",
                                // Fase 7C: headers de webhook de Mercado Pago
                                "x-signature", "x-request-id"));

                // Headers expuestos al cliente
                config.setExposedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Total-Count"));

                // Permitir credenciales (cookies, auth headers)
                config.setAllowCredentials(true);

                // Cache preflight por 1 hora
                config.setMaxAge(3600L);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", config);
                return source;
        }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                http
                                // Configuración CORS
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                                // Deshabilitar CSRF para API REST
                                .csrf(csrf -> csrf.disable())

                                // Deshabilitar form login y http basic (evita redirecciones 302)
                                .formLogin(form -> form.disable())
                                .httpBasic(basic -> basic.disable())

                                // Manejo de excepciones - devolver 401 en lugar de redirigir
                                .exceptionHandling(exception -> exception
                                                .authenticationEntryPoint(unauthorizedHandler))

                                // Sin sesiones (stateless para JWT)
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                                // Headers de seguridad COMPLETOS para producción
                                .headers(headers -> headers
                                                // X-Frame-Options: Previene clickjacking
                                                .frameOptions(frameOptions -> frameOptions.deny())

                                                // X-Content-Type-Options: Previene MIME sniffing
                                                // X-Content-Type-Options: nosniff está habilitado por defecto en Spring
                                                // Security

                                                // X-XSS-Protection: Protección XSS del navegador
                                                .xssProtection(xss -> xss.headerValue(
                                                                org.springframework.security.web.header.writers.XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))

                                                // Content-Security-Policy: Previene XSS y injection
                                                .contentSecurityPolicy(csp -> csp.policyDirectives(
                                                                "default-src 'self'; " +
                                                                                "script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
                                                                                +
                                                                                "style-src 'self' 'unsafe-inline'; " +
                                                                                "img-src 'self' data: https:; " +
                                                                                "font-src 'self' https:; " +
                                                                                "connect-src 'self' https:; " +
                                                                                "frame-ancestors 'none'"))

                                                // HTTP Strict Transport Security: Fuerza HTTPS
                                                .httpStrictTransportSecurity(hsts -> hsts
                                                                .maxAgeInSeconds(31536000) // 1 año
                                                                .includeSubDomains(true)
                                                                .preload(true))

                                                // Referrer-Policy: Controla información de referencia
                                                .referrerPolicy(referrer -> referrer
                                                                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))

                                // Autorización de endpoints
                                .authorizeHttpRequests(authz -> authz
                                                // CORS preflight - SIEMPRE permitir OPTIONS primero
                                                .requestMatchers("/api/2fa/debug/**").denyAll()
                                                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                                                // ========== ENDPOINTS PÚBLICOS ==========
                                                // API pública del Storefront (solo lectura explícita)
                                                .requestMatchers(HttpMethod.GET, "/api/products/catalog").permitAll()
                                                .requestMatchers(HttpMethod.GET, "/api/products/catalog/**").permitAll()
                                                .requestMatchers(HttpMethod.GET, "/api/products/*").permitAll() // Para detalle por ID
                                                .requestMatchers(HttpMethod.GET, "/api/categories/**").permitAll()
                                                .requestMatchers(HttpMethod.GET, "/api/brands/**").permitAll()
                                                .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                                                .requestMatchers(HttpMethod.GET, "/api/reviews/product/**").permitAll()
                                                .requestMatchers(HttpMethod.GET, "/api/alexa/**").permitAll()

                                                // Autenticación
                                                .requestMatchers(com.security.security.PublicAuthRoutes.EXACT).permitAll()
                                                .requestMatchers(com.security.security.PublicAuthRoutes.PATTERNS).permitAll()
                                                .requestMatchers("/api/auth/**").authenticated()

                                                // FASE 0 - Seguridad - 2026-05-15
                                                // /api/test/** restringido a SYSTEM_SETTINGS como segunda capa de defensa.
                                                // TestController además está anotado con @Profile({"local","dev"})
                                                // y no se registra en producción.
                                                .requestMatchers("/api/test/**").hasAuthority("SYSTEM_SETTINGS")
                                                // Health check — solo /actuator/health es público
                                                .requestMatchers("/actuator/health").permitAll()
                                                .requestMatchers("/actuator/**").hasAuthority("SYSTEM_SETTINGS")
                                                .requestMatchers("/error").permitAll()

                                                // Fase 7C: Webhook de Mercado Pago — público pero protegido por HMAC x-signature
                                                // NO requiere JWT. La seguridad real es la validación HMAC en MercadoPagoWebhookService.
                                                .requestMatchers(HttpMethod.POST, "/api/webhooks/mercado-pago").permitAll()

                                                // FASE 0 - Seguridad - 2026-05-15
                                                // Swagger/OpenAPI: público solo en perfiles local/dev.
                                                // En producción requiere SYSTEM_SETTINGS.
                                                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**")
                                                .access((authentication, context) -> {
                                                    boolean isDevProfile = environment.acceptsProfiles(Profiles.of("local", "dev"));
                                                    if (isDevProfile) {
                                                        return new org.springframework.security.authorization.AuthorizationDecision(true);
                                                    }
                                                    boolean hasSystemSettings = authentication.get().getAuthorities()
                                                            .stream()
                                                            .anyMatch(a -> "SYSTEM_SETTINGS".equals(a.getAuthority()));
                                                    return new org.springframework.security.authorization.AuthorizationDecision(hasSystemSettings);
                                                })

                                                // FASE 0 - Seguridad - 2026-05-15
                                                // H2 Console: denyAll() en todos los perfiles.
                                                // El proyecto usa PostgreSQL; H2 no debe estar accesible en ningún entorno.
                                                // Si se requiere H2 en local/dev en el futuro, usar:
                                                //   environment.acceptsProfiles(Profiles.of("local","dev")) ? permitAll() : denyAll()
                                                .requestMatchers("/h2-console/**").denyAll()

                                                // Upload de archivos y servir imágenes estáticas
                                                // Staff con permisos de producto también necesitan subir imágenes
                                                .requestMatchers("/api/upload/**").authenticated()
                                                .requestMatchers("/uploads/**").permitAll() // Servir archivos estáticos

                                                // ========== ENDPOINTS PROTEGIDOS ==========
                                                // Administración — delegamos la autorización granular a @PreAuthorize
                                                // en cada controller. Solo exigimos autenticación a nivel de filtro.
                                                .requestMatchers("/api/admin/**").authenticated()

                                                // 2FA setup/config (requiere estar logueado) - EXCLUYE los ya
                                                // permitidos
                                                .requestMatchers("/api/2fa/google/**").authenticated()
                                                .requestMatchers("/api/2fa/email/**").authenticated()
                                                .requestMatchers("/api/2fa/backup-codes/**").authenticated()
                                                .requestMatchers("/api/2fa/status").authenticated()

                                                // FASE 0 - Seguridad - 2026-05-15
                                                // Reglas específicas de test COMENTADAS — son código muerto.
                                                // La regla general .requestMatchers("/api/test/**").hasAuthority("SYSTEM_SETTINGS")
                                                // (línea superior) consume todos los paths /api/test/** antes de llegar aquí
                                                // (Spring Security aplica first-match-wins).
                                                // Todo /api/test/** requiere SYSTEM_SETTINGS. No hay excepciones.
                                                // .requestMatchers("/api/test/protected").authenticated()
                                                // .requestMatchers("/api/test/admin").authenticated()

                                                // Usuarios - usuarios normales pueden ver su perfil y cambiar
                                                // contraseña, admins todo
                                                .requestMatchers("/api/users/profile").authenticated()
                                                .requestMatchers("/api/users/**").authenticated()

                                                // Carrito de compras - solo usuarios autenticados
                                                .requestMatchers("/api/cart/**").authenticated()

                                                // Todo lo demás requiere autenticación
                                                .anyRequest().authenticated());

                // Agregar providers y filtros
                http.authenticationProvider(authenticationProvider());
                http.addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }
}