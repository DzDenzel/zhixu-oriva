package com.planlock.app.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.account.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(SetupEndpointTest.Config.class)
@TestPropertySource(properties={"planlock.deployment.enabled=true", "planlock.deployment.setup-token=install-secret"})
class SetupEndpointTest {
    @Configuration @EnableWebMvc @ComponentScan(basePackages="com.planlock.app.setup", useDefaultFilters=false, includeFilters=@ComponentScan.Filter(type=FilterType.REGEX, pattern="com.planlock.app.setup.(SetupController|SetupService)"))
    static class Config {
        @Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
        @Bean AuthService authService() { return mock(AuthService.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired StringRedisTemplate redis;
    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();
    Map<String,Object> state;
    @BeforeEach void prepare() {
        reset(jdbc,auth,redis);
        state = new HashMap<>(Map.of("instance_id","2c6f9664-1c46-4b9f-9710-cac1de5e389d", "initialized",false));
        when(jdbc.queryForMap(anyString())).thenAnswer(i -> new HashMap<>(state));
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("4");
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(i -> { state.put("initialized",true); return 1; });
        var factory = mock(RedisConnectionFactory.class); var connection = mock(RedisConnection.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection); when(connection.ping()).thenReturn("PONG");
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
    String body(String token,String url) throws Exception {
        return json.writeValueAsString(Map.of("setupToken",token,"instanceLabel","我的知序", "account","owner@example.org", "password","owner-password", "accessUrl",url));
    }
    @Test void invalidTokenCreatesNothing() throws Exception {
        mvc.perform(post("/api/v1/setup").contentType("application/json").content(body("wrong","http://192.168.1.8:8080"))).andExpect(status().isForbidden());
        verifyNoInteractions(auth); verify(jdbc,never()).update(anyString(), any(Object[].class));
    }
    @Test void healthIsPublicRawAndSecretFree() throws Exception {
        var response=mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doc=json.readTree(response);
        assertThat(doc.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("status","version","migrationVersion","instanceId","initialized");
        assertThat(doc.path("status").asText()).isEqualTo("UP");
        assertThat(response).doesNotContain("install-secret","jdbc:","password","redis");
    }
    @Test void unhealthyDependencyFailsReadinessWithoutDiagnostics() throws Exception {
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenThrow(new IllegalStateException("jdbc:secret-password"));
        var response=mvc.perform(get("/api/v1/health")).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("DOWN").doesNotContain("secret-password");
    }
    @Test void invalidAddressesNeverCreateAnAccount() throws Exception {
        for(String url:List.of("http://example.org","https://" + "user:secret@example.org","https://example.org?a=1","https://example.org/#token", "ftp://localhost", "https://example.org/other", "http://8.8.8.8", "https://example.org:0")) {
            mvc.perform(post("/api/v1/setup").contentType("application/json").content(body("install-secret",url))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(auth);
    }
    @Test void initializedDeploymentClosesPageAndRefusesSecondSetup() throws Exception {
        state.put("initialized",true);
        mvc.perform(get("/setup")).andExpect(status().isGone());
        mvc.perform(post("/api/v1/setup").contentType("application/json").content(body("install-secret","https://example.org"))).andExpect(status().isConflict());
        verifyNoInteractions(auth);
    }
    @Test void setupPageContainsLocalAccessibleForm() throws Exception {
        var html=mvc.perform(get("/setup")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("初始化","setupToken","accessUrl","password").doesNotContain("cdn.", "https://fonts", "install-secret");
    }
    @Test void privateAddressesProduceExactlyFiveFieldsAndQrRoundTripsUtf8() throws Exception {
        for(String url:List.of("http://192.168.1.8:8080", "http://[fd12::1]:8080", "http://[::1]:8080", "http://my-nas.local:8080", "http://nas:8080")) {
            state.put("initialized",false);
            var response=mvc.perform(post("/api/v1/setup").contentType("application/json").content(body("install-secret",url))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var result=json.readTree(response); var connection=result.path("connection");
            assertThat(connection.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("version","instanceLabel","apiBaseUrl","pluginsCatalogUrl","lanAllowed");
            assertThat(connection.path("apiBaseUrl").asText()).isEqualTo(url+"/api/v1/");
            assertThat(connection.path("pluginsCatalogUrl").asText()).isEqualTo(url+"/api/v1/plugins/catalog/");
            assertThat(connection.path("lanAllowed").asBoolean()).isTrue();
            assertThat(response).doesNotContain("install-secret","owner-password","owner@example.org");
            byte[] png=Base64.getDecoder().decode(result.path("qrDataUrl").asText().substring("data:image/png;base64,".length()));
            var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            int[] pixels=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
            var source=new com.google.zxing.RGBLuminanceSource(image.getWidth(),image.getHeight(),pixels);
            var decoded=new com.google.zxing.MultiFormatReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source)));
            assertThat(json.readTree(decoded.getText())).isEqualTo(connection);
        }
    }
    @Test void publicHttpsDoesNotEnableLan() throws Exception {
        var response=mvc.perform(post("/api/v1/setup").contentType("application/json").content(body("install-secret","https://example.org"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).path("connection").path("lanAllowed").asBoolean()).isFalse();
    }
    @Test void malformedRequestAndMissingPasswordCreateNothing() throws Exception {
        mvc.perform(post("/api/v1/setup").contentType("application/json").content("{\"password\":")).andExpect(status().isBadRequest());
        var request=json.readTree(body("install-secret","https://example.org")); ((com.fasterxml.jackson.databind.node.ObjectNode)request).put("password","");
        mvc.perform(post("/api/v1/setup").contentType("application/json").content(request.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(auth);
    }
    @Test void unicodeSpaceOnlyLabelsAreRejectedBeforeOwnerCreation() throws Exception {
        for (String label : List.of("\u00a0", "\u2007", "\u202f", " \u00a0\u2007\u202f ")) {
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body("install-secret","https://example.org"));
            request.put("instanceLabel",label);
            mvc.perform(post("/api/v1/setup").contentType("application/json").content(request.toString())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(auth);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void labelEdgesUseAppWhitespaceRulesAndPreserveInternalSpaces() throws Exception {
        for (String edge : List.of("\u00a0", "\u2007", "\u202f", " \u00a0\u2007\u202f ")) {
            state.put("initialized",false);
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body("install-secret","https://example.org"));
            request.put("instanceLabel",edge+"我的\u00a0知序"+edge);
            var response=mvc.perform(post("/api/v1/setup").contentType("application/json").content(request.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(json.readTree(response).path("connection").path("instanceLabel").asText()).isEqualTo("我的\u00a0知序");
        }
    }
    @Test void accountEdgesUseAppWhitespaceRulesWhilePasswordRemainsExact() throws Exception {
        for (String edge : List.of("\u00a0", "\u2007", "\u202f", " \u00a0\u2007\u202f ")) {
            state.put("initialized",false);
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body("install-secret","https://example.org"));
            request.put("account",edge+"owner@example.org"+edge);
            request.put("password"," owner-password ");
            mvc.perform(post("/api/v1/setup").contentType("application/json").content(request.toString())).andExpect(status().isOk());
        }
        verify(auth,times(4)).createSetupOwner("owner@example.org"," owner-password ");
        verifyNoMoreInteractions(auth);
    }
}
