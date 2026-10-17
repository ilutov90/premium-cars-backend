package com.premiumcars.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

/**
 * application.yml + a real Vault: the keys of secret/premium-cars/backend (the same names as the old .env) fill
 * the placeholders, e.g. spring.datasource.password = ${DB_PASSWORD}. Token auth here; the AppRole setup is
 * tested in premium-cars-infra's CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class VaultConfigTest {

    @Container
    static VaultContainer<?> vault = new VaultContainer<>("hashicorp/vault:1.20")
            .withVaultToken("test-root-token")
            .withInitCommand("kv put secret/premium-cars/backend DB_USERNAME=app DB_PASSWORD=db-from-vault "
                    + "AUTOWINI_USER=from-vault FB_ACCESS_TOKEN=token-from-vault ADMIN_INITIAL_PASSWORD=first-admin-pw");

    /** No beans: only the configuration is under test. */
    @SpringBootConfiguration
    static class ConfigOnly {
    }

    @Test
    void secretsComeFromVaultUnderTheirEnvNames() {
        try (var context = new SpringApplicationBuilder(ConfigOnly.class).web(WebApplicationType.NONE).run(
                "--spring.cloud.vault.enabled=true",
                "--spring.cloud.vault.uri=" + vault.getHttpHostAddress(),
                "--spring.cloud.vault.authentication=TOKEN",
                "--spring.cloud.vault.token=test-root-token")) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("app");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("db-from-vault");
            assertThat(env.getProperty("app.importer.autowini.username")).isEqualTo("from-vault");
            assertThat(env.getProperty("app.social.access-token")).isEqualTo("token-from-vault");
            // app.admin.initial-password itself is set by src/test/resources/application.properties
            assertThat(env.getProperty("ADMIN_INITIAL_PASSWORD")).isEqualTo("first-admin-pw");
        }
    }

    @Test
    void startFailsWhenVaultRefusesTheLogin() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new SpringApplicationBuilder(ConfigOnly.class).web(WebApplicationType.NONE).run(
                                "--spring.cloud.vault.enabled=true",
                                "--spring.cloud.vault.uri=" + vault.getHttpHostAddress(),
                                "--spring.cloud.vault.authentication=TOKEN",
                                "--spring.cloud.vault.token=wrong-token").close())
                .isInstanceOf(Exception.class);
    }
}
