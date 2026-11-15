package com.security.controller.admin;

import com.security.support.LocalPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.mercadopago.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminBackupAccessIntegrationTest extends LocalPostgresIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void permitsTheTableSelectorOnlyWithTheBackupAuthority() throws Exception {
        String path = "/api/admin/backups/tables";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user("viewer").authorities(new SimpleGrantedAuthority("DATABASE_VIEW"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(user("backup-operator").authorities(new SimpleGrantedAuthority("DATABASE_BACKUP"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("catalog.products")));
    }
}
