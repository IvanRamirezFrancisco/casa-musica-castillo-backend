-- ============================================================================
-- V20: Tabla de invitaciones para empleados (Staff)
-- Flujo: Admin invita → empleado recibe email → activa su cuenta
-- ============================================================================
CREATE TABLE public.staff_invitations (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(100) NOT NULL,
    token VARCHAR(64) NOT NULL UNIQUE,
    invited_by BIGINT NOT NULL REFERENCES public.users(id),
    role_ids TEXT NOT NULL,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL DEFAULT (NOW() + INTERVAL '48 hours'),
    accepted_at TIMESTAMP,
    CONSTRAINT chk_invitation_status CHECK (
        status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'CANCELLED')
    )
);
-- Índices para consultas frecuentes
CREATE INDEX idx_invitations_token ON public.staff_invitations(token);
CREATE INDEX idx_invitations_email ON public.staff_invitations(email);
CREATE INDEX idx_invitations_status ON public.staff_invitations(status);