package com.planlock.app.setup;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConnectionDocumentTest {
    @Test void independentPrivatePluginSourceEnablesLanWhilePublicApiRemainsHttps() {
        var result=SetupService.connection("实例","https://example.org/api/v1/","http://plugins.lan:8080/catalog");
        assertThat(result.get("apiBaseUrl")).isEqualTo("https://example.org/api/v1/");
        assertThat(result.get("pluginsCatalogUrl")).isEqualTo("http://plugins.lan:8080/catalog/");
        assertThat(result.get("lanAllowed")).isEqualTo(true);
    }
    @Test void publicHttpPluginOverrideAndCredentialUrlsAreRejected() {
        for(String override:java.util.List.of("http://plugins.example.org/catalog", "https://" + "name:secret@plugins.example.org/catalog", "https://plugins.example.org/catalog?token=secret", "http://[2001:db8::1]/catalog")) {
            assertThatThrownBy(()->SetupService.connection("实例","https://example.org",override)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }
    @Test void publicIpv6HttpsAndLinkLocalHttpMatchClientSchema() {
        assertThat(SetupService.connection("实例","https://[2001:db8::1]","").get("lanAllowed")).isEqualTo(false);
        assertThat(SetupService.connection("实例","http://[fe80::1]:8080","").get("lanAllowed")).isEqualTo(true);
        assertThat(SetupService.connection("实例","http://169.254.1.8:8080","").get("lanAllowed")).isEqualTo(true);
    }
    @Test void percentEncodedPluginUrlMustStillFitClientLengthLimit() {
        assertThatThrownBy(()->SetupService.connection("实例","https://example.org","https://plugins.example.org/"+"序".repeat(700)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void scopedIpv6IsRejectedBecauseAppHttpUrlDoesNotSupportZoneIds() {
        assertThatThrownBy(()->SetupService.connection("实例","http://[fe80::1%251]:8080", ""))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
