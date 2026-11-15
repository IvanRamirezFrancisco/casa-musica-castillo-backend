package com.security.exception;

/**
 * Excepción lanzada cuando un usuario intenta realizar una operación
 * administrativa sobre otro usuario para el cual no tiene suficiente jerarquía.
 * (ej. Un ADMIN intentando modificar a un SUPER_ADMIN, o alguien intentando
 * modificar al PROTECTED_OWNER).
 */
public class SecurityHierarchyException extends RuntimeException {

    public SecurityHierarchyException(String message) {
        super(message);
    }
}
