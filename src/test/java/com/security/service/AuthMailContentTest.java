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
        assertThat(updated.html()).contains("Contrase&ntilde;a actualizada", "Hola");
        assertThat(updated.text()).contains("comunícate con soporte");
    }
}
