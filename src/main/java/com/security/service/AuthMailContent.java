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
                content, "El enlace y el código caducan en 24 horas. Solo puedes usar uno. Si no creaste esta cuenta, ignora este correo.",
                "Verificar mi cuenta: " + url + "\nCódigo de activación: " + code);
    }

    static AuthMailContent passwordChanged(String name) {
        return render("Contraseña actualizada", name, "La contraseña de tu cuenta se actualizó.",
                "", "Si no reconoces este cambio, comunícate con soporte.", "");
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
