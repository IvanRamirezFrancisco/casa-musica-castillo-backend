package com.security.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuración para definir las cuentas de propietarios técnicos.
 * Estas cuentas son intocables por otros usuarios, incluyendo otros SUPER_ADMINs.
 */
@Configuration
@ConfigurationProperties(prefix = "app.security")
@Data
public class ProtectedOwnerProperties {
    
    /**
     * Lista de correos electrónicos de los propietarios del sistema.
     * Estos usuarios tendrán protección máxima contra modificaciones, eliminaciones
     * o degradaciones de jerarquía.
     */
    private List<String> protectedOwnerEmails;

}
