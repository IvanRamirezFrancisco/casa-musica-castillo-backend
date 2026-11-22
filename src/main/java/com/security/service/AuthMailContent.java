package com.security.service;

import org.springframework.web.util.HtmlUtils;

/** Email-safe presentation shared by the authentication notifications. */
record AuthMailContent(String html, String text) {
    static AuthMailContent link(String title, String name, String message, String action, String url, String note) {
        String safeUrl = HtmlUtils.htmlEscape(url);
        String content = """
                <p><a href="%s" style="display:inline-block;background:#B84A22;color:#FFFFFF;padding:12px 20px;border-radius:6px;font-weight:bold;text-decoration:none">%s</a></p>
                <p style="font-size:14px;word-break:break-all">También puedes pegar este enlace completo en la app: %s</p>
                """.formatted(safeUrl, HtmlUtils.htmlEscape(action), safeUrl);
        return render(title, name, message, content, note, action + ": " + url);
    }

    static AuthMailContent code(String name, String code) {
        return render("Código de verificación", name, "Usa este código para completar el inicio de sesión.",
                """
                <div style="display:inline-block;background:#FFCF71;color:#292524;padding:10px 16px;font:bold 26px monospace"><div class="code">%s</div></div>
                """.formatted(HtmlUtils.htmlEscape(code)),
                "Caduca en cinco minutos y es de un solo uso. Si no lo solicitaste, ignora este correo. No compartas el código.",
                "Código: " + code);
    }

    static AuthMailContent activation(String name, String url, String code) {
        String safeUrl = HtmlUtils.htmlEscape(url);
        String content = """
                <p><a href="%s" style="display:inline-block;background:#722F37;color:#fff;padding:12px 20px;border-radius:8px;text-decoration:none">Verificar mi cuenta</a></p>
                <p>También puedes ingresar este código de activación:</p>
                <p style="display:inline-block;background:#FFCF71;color:#292524;padding:12px 18px;font:bold 26px monospace;letter-spacing:4px">%s</p>
                """.formatted(safeUrl, HtmlUtils.htmlEscape(code));
        return render("Verifica tu correo electrónico", name, "Activa tu cuenta de Casa de Música Castillo.",
                content, "El código caduca en 15 minutos o antes según la política del servicio. El enlace tiene su propia vigencia. Solo puedes usar uno. Si no creaste esta cuenta, ignora este correo.",
                "Verificar mi cuenta: " + url + "\nCódigo de activación: " + code);
    }

    static AuthMailContent passwordChanged(String name) {
        String message = "Te confirmamos que la contraseña de tu cuenta de Casa de Música Castillo se cambió correctamente.";
        String extra = "<p style=\"margin:0 0 28px;line-height:1.65\">Si realizaste este cambio, no necesitas hacer nada más.</p>";
        String warning = "Si no realizaste este cambio, comunícate con nuestro equipo de soporte lo antes posible.";
        return passwordLayout(name, "Tu contraseña fue actualizada", message, extra,
                "¿No reconoces esta actividad?", warning,
                "Si realizaste este cambio, no necesitas hacer nada más.\n\n¿No reconoces esta actividad?\n" + warning);
    }

    static AuthMailContent passwordReset(String name, String url, String expiryNote) {
        String safeUrl = HtmlUtils.htmlEscape(url);
        String action = """
                <table role="presentation" cellspacing="0" cellpadding="0" style="margin:8px 0 24px"><tr><td bgcolor="#722F37" style="border-radius:10px;background:#722F37">
                <a href="%s" style="display:inline-block;padding:16px 24px;font-weight:bold;color:#FFFFFF;text-decoration:none;line-height:1.5">Restablecer contraseña</a>
                </td></tr></table>
                <p style="margin:0 0 24px;font-size:14px;line-height:1.6;overflow-wrap:anywhere;word-break:break-all">Si el botón no abre el formulario, copia este enlace en tu navegador:<br><a href="%s" style="color:#722F37;word-break:break-all">%s</a></p>
                """.formatted(safeUrl, safeUrl, safeUrl);
        return passwordLayout(name, "Restablece tu contraseña",
                "Recibimos una solicitud para restablecer la contraseña de tu cuenta de Casa de Música Castillo.",
                action, "Un enlace personal y temporal", expiryNote,
                "Restablecer contraseña: " + url + "\n\n" + expiryNote);
    }

    /** Email-only frame. Activation and MFA keep their existing templates. No external assets. */
    private static AuthMailContent passwordLayout(String name, String title, String message, String extra,
            String noteTitle, String note, String plainExtra) {
        String greeting = name == null || name.isBlank() ? "Hola" : "Hola, " + name.strip();
        String html = """
                <!DOCTYPE html>
                <html lang="es"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="color-scheme" content="light dark"><meta name="supported-color-schemes" content="light dark"><title>Seguridad de tu cuenta</title></head>
                <body style="margin:0;padding:0;background:#FCFAF7;color:#292524;font-family:Arial,Helvetica,sans-serif;-webkit-text-size-adjust:100%%">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" bgcolor="#FCFAF7" style="width:100%%;background:#FCFAF7"><tr><td align="center" style="padding:24px 12px">
                <!--[if mso]><table role="presentation" width="600" cellspacing="0" cellpadding="0" border="0"><tr><td><![endif]-->
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="width:100%%;max-width:600px;border:1px solid #E7DED5;border-radius:20px;background:#FFFFFF" bgcolor="#FFFFFF">
                <tr><td bgcolor="#722F37" style="padding:28px 24px;background:#722F37;border-radius:20px 20px 0 0;border-bottom:3px solid #C5A059">
                <p style="margin:0 0 8px;color:#FFFFFF;font-size:22px;font-weight:bold;line-height:1.35">Casa de Música Castillo</p>
                <p style="margin:0;color:#F4E8E5;font-size:14px;line-height:1.5">Seguridad de tu cuenta</p></td></tr>
                <tr><td style="padding:32px 24px;color:#292524;background:#FFFFFF">
                <table role="presentation" width="56" height="56" cellspacing="0" cellpadding="0" border="0" style="margin-bottom:24px"><tr><td align="center" valign="middle" bgcolor="#E5EEE7" style="width:56px;height:56px;background:#E5EEE7;border-radius:28px;color:#30533F;font-size:28px;font-family:Arial,sans-serif" aria-hidden="true">&#10003;</td></tr></table>
                <p style="margin:0 0 12px;color:#625C57;font-size:16px;line-height:1.6">%s</p>
                <h1 style="margin:0 0 20px;color:#292524;font-size:28px;font-weight:bold;line-height:1.3">%s</h1>
                <p style="margin:0 0 20px;color:#292524;font-size:16px;line-height:1.65">%s</p>%s
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" bgcolor="#F7F0EC" style="background:#F7F0EC;border-radius:12px;border-left:3px solid #C5A059"><tr><td style="padding:20px;color:#49252C">
                <h2 style="margin:0 0 8px;font-size:17px;line-height:1.4;color:#49252C">%s</h2><p style="margin:0;font-size:15px;line-height:1.65;color:#49252C">%s</p>
                </td></tr></table></td></tr>
                <tr><td bgcolor="#FCFAF7" style="padding:24px;border-top:1px solid #E7DED5;border-radius:0 0 20px 20px;background:#FCFAF7;color:#625C57">
                <p style="margin:0 0 6px;font-size:14px;font-weight:bold;line-height:1.5">Casa de Música Castillo</p><p style="margin:0;font-size:13px;line-height:1.6">Este es un mensaje automático de seguridad de tu cuenta.</p>
                </td></tr></table><!--[if mso]></td></tr></table><![endif]--></td></tr></table></body></html>
                """.formatted(HtmlUtils.htmlEscape(greeting), HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(message),
                    extra, HtmlUtils.htmlEscape(noteTitle), HtmlUtils.htmlEscape(note));
        String text = String.join("\n\n", "Casa de Música Castillo — Seguridad de tu cuenta", greeting, title,
                message, plainExtra, "Casa de Música Castillo\nEste es un mensaje automático de seguridad de tu cuenta.");
        return new AuthMailContent(html, text);
    }

    private static AuthMailContent render(String title, String name, String message, String extra,
                                          String note, String plainExtra) {
        String greeting = name == null || name.isBlank() ? "Hola" : "Hola, " + name.strip();
        String html = """
                <!DOCTYPE html>
                <html lang="es"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                <body style="margin:0;background:#FCFAF7;color:#292524;font:16px Arial,sans-serif">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#FCFAF7"><tr><td align="center" style="padding:16px">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:600px;background:#FFFFFF;border:1px solid #E7DED5">
                <tr><td style="background:#722F37;color:#FFFFFF;padding:20px"><p>Casa de Música Castillo</p><h1 style="margin:0;font-size:24px">%s</h1></td></tr>
                <tr><td style="padding:20px;line-height:1.5"><p>%s</p><p>%s</p>%s
                <p style="padding:12px;background:#F5EFE8;border-left:4px solid #FFCF71;color:#292524">%s</p></td></tr>
                <tr><td style="padding:16px;border-top:1px solid #E7DED5;color:#6B6560;font-size:14px">Casa de Música Castillo</td></tr>
                </table></td></tr></table></body></html>
                """.formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(greeting),
                HtmlUtils.htmlEscape(message), extra, HtmlUtils.htmlEscape(note));
        return new AuthMailContent(html, String.join("\n\n", title, greeting, message, plainExtra, note));
    }
}
