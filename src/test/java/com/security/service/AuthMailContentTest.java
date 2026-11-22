package com.security.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthMailContentTest {
    @Test
    void includesTheActionInBothFormatsAndEscapesHtml() {
        var content = AuthMailContent.link("Verificación", "<img src=x onerror=alert(1)>", "Verifica tu cuenta.",
                "Verificar", "http://127.0.0.1:4200/verify-account?token=fake&test=true", "Ignora si no lo solicitaste.");

        assertThat(content.html()).contains("#722F37", "#B84A22", "&lt;img", "token=fake&amp;test=true")
                .doesNotContain("<img");
        assertThat(content.text()).contains("Verificar", "token=fake&test=true");
    }

    @Test
    void rendersTheMfaAndPasswordChangeMessages() {
        var mfa = AuthMailContent.code("QA", "012345");
        assertThat(mfa.html()).contains("class=\"code\">012345", "#FFCF71");
        assertThat(mfa.text()).contains("012345", "cinco minutos");
        var updated = AuthMailContent.passwordChanged(null);
        assertThat(updated.html()).contains("Tu contrase&ntilde;a fue actualizada", "Hola");
        assertThat(updated.text()).contains("comunícate con nuestro equipo de soporte");
    }
    @Test void passwordNoticeIsBrandedEscapedAndHasNoBearerOrExternalAssets() {
        var notice=AuthMailContent.passwordChanged("<script>alert('synthetic')</script>");
        assertThat(notice.html()).contains("max-width:600px", "role=\"presentation\"", "#722F37", "#C5A059", "#E5EEE7", "&lt;script&gt;", "Seguridad de tu cuenta");
        assertThat(notice.html()).doesNotContain("<script>", "<svg", "<img", "href=", "token=", "unsubscribe", "@import");
        assertThat(notice.text()).contains("Tu contraseña fue actualizada", "Si realizaste este cambio", "¿No reconoces esta actividad?", "Este es un mensaje automático");
    }
    @Test void recoveryAndNoticeShareBrandButNeverShareTheirSecretsOrPurpose() {
        var reset=AuthMailContent.passwordReset("Sintética", "https://shop.example.test/reset-password?token=synthetic-reset&x=1", "Caduca en 60 minutos. Si no lo solicitaste, ignora este correo.");
        var notice=AuthMailContent.passwordChanged("Sintética");
        assertThat(reset.html()).contains("token=synthetic-reset&amp;x=1", "#722F37", "max-width:600px", "Restablecer contraseña");
        assertThat(reset.text()).contains("token=synthetic-reset&x=1", "60 minutos");
        assertThat(notice.html()).doesNotContain("synthetic-reset", "/reset-password", "60 minutos");
        assertThat(notice.text()).doesNotContain("token=", "Restablecer contraseña:");
    }

}
