package com.planlock.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.PlanLockApplication;
import com.planlock.account.entity.Device;
import com.planlock.account.entity.User;
import com.planlock.account.mapper.DeviceMapper;
import com.planlock.account.mapper.UserMapper;
import com.planlock.account.service.DeploymentAuthLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 Spring Boot 端到端登录 -> /api/v1/me 链路测试：
 * 覆盖 platform="web" 登录签发 JWT、真实 owner_user_id 判定 OWNER vs STANDARD 与设备解绑清空。
 */
@SpringBootTest(
        classes = {PlanLockApplication.class, WebPortalLoginToMeBootTest.MockDataSourceConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "planlock.deployment.enabled=true",
        "planlock.deployment.setup-token=login-me-secret",
        "planlock.version=2.2.0",
        "planlock.jwt.secret=planlock-login-to-me-boot-secret-32b",
        "planlock.jwt.expire-minutes=60"
})
class WebPortalLoginToMeBootTest {

    @TestConfiguration
    static class MockDataSourceConfig {
        @Bean
        @Primary
        DataSource dataSource() throws Exception {
            DataSource ds = mock(DataSource.class);
            java.sql.Connection conn = mock(java.sql.Connection.class);
            when(conn.getAutoCommit()).thenReturn(true);
            when(ds.getConnection()).thenReturn(conn);
            return ds;
        }
    }

    @MockitoBean
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;
    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private DeviceMapper deviceMapper;
    @MockitoBean
    private DeploymentAuthLimiter deploymentAuthLimiter;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ObjectMapper json;

    private final Map<Long, Device> deviceStore = new HashMap<>();
    private final AtomicLong nextDeviceId = new AtomicLong(100L);

    @BeforeEach
    void setUp() {
        deviceStore.clear();
        when(deploymentAuthLimiter.sourceKey()).thenReturn("src:test");
        when(deploymentAuthLimiter.accountKey(any(), anyString())).thenReturn("acc:test");

        Map<String, Object> deploymentRow = new HashMap<>();
        deploymentRow.put("instance_id", "2c6f9664-1c46-4b9f-9710-cac1de5e389d");
        deploymentRow.put("initialized", true);
        deploymentRow.put("instance_label", "书房服务器");
        deploymentRow.put("owner_user_id", 10L);
        when(jdbcTemplate.queryForMap(anyString())).thenAnswer(inv -> new HashMap<>(deploymentRow));

        User owner = new User();
        owner.setId(10L);
        owner.setEmail("owner@oriva.local");
        owner.setPasswordHash(passwordEncoder.encode("owner-pass-123"));

        User member = new User();
        member.setId(20L);
        member.setPhone("13812345678");
        member.setPasswordHash(passwordEncoder.encode("member-pass-123"));

        when(userMapper.findByAccount("owner@oriva.local")).thenReturn(owner);
        when(userMapper.findById(10L)).thenReturn(owner);
        when(userMapper.findByAccount("13812345678")).thenReturn(member);
        when(userMapper.findById(20L)).thenReturn(member);

        when(deviceMapper.findByDeviceUid(anyString())).thenAnswer(inv -> {
            String uid = inv.getArgument(0);
            return deviceStore.values().stream().filter(d -> uid.equals(d.getDeviceUid())).findFirst().orElse(null);
        });
        when(deviceMapper.findById(any())).thenAnswer(inv -> deviceStore.get(inv.<Long>getArgument(0)));
        when(deviceMapper.findByUserId(any())).thenAnswer(inv -> {
            Long uid = inv.getArgument(0);
            return deviceStore.values().stream().filter(d -> uid.equals(d.getUserId())).toList();
        });
        when(deviceMapper.insert(any(Device.class))).thenAnswer(inv -> {
            Device d = inv.getArgument(0);
            d.setId(nextDeviceId.incrementAndGet());
            deviceStore.put(d.getId(), d);
            return 1;
        });
    }

    @Test
    void webPlatformLoginIssuesJwtAndMeReturnsVerifiedOwnerStandardAndUnboundDeviceStates() throws Exception {
        // 1. Owner logs in with platform="web"
        String loginBody = json.writeValueAsString(Map.of(
                "account", "owner@oriva.local",
                "password", "owner-pass-123",
                "deviceUid", "web-550e8400-e29b-41d4-a716-446655440000",
                "deviceName", "知序 Web（Edge）",
                "platform", "web"
        ));
        String loginRes = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        var loginDoc = json.readTree(loginRes);
        String ownerToken = loginDoc.path("data").path("token").asText();
        long ownerDeviceId = loginDoc.path("data").path("deviceId").asLong();
        assertThat(ownerToken).isNotBlank();

        // 2. Owner calls GET /api/v1/me -> HTTP 200, OWNER, masked email, real instanceLabel, platform="web"
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(10))
                .andExpect(jsonPath("$.data.deviceId").value(ownerDeviceId))
                .andExpect(jsonPath("$.data.accountMasked").value("ow***r@oriva.local"))
                .andExpect(jsonPath("$.data.userType").value("OWNER"))
                .andExpect(jsonPath("$.data.devicePlatform").value("web"))
                .andExpect(jsonPath("$.data.instanceLabel").value("书房服务器"));

        // 3. Unbind device -> GET /api/v1/me clears deviceId/deviceName/devicePlatform completely
        deviceStore.remove(ownerDeviceId);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deviceId").doesNotExist())
                .andExpect(jsonPath("$.data.deviceName").doesNotExist())
                .andExpect(jsonPath("$.data.devicePlatform").doesNotExist());

        // 4. Non-owner account logs in with platform="web" -> GET /api/v1/me returns STANDARD (never OWNER)
        String memberLoginBody = json.writeValueAsString(Map.of(
                "account", "13812345678",
                "password", "member-pass-123",
                "deviceUid", "web-550e8400-e29b-41d4-a716-446655440001",
                "deviceName", "Member Browser",
                "platform", "web"
        ));
        String memberToken = json.readTree(
                mvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(memberLoginBody))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0))
                        .andReturn().getResponse().getContentAsString()
        ).path("data").path("token").asText();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(20))
                .andExpect(jsonPath("$.data.userType").value("STANDARD"))
                .andExpect(jsonPath("$.data.accountMasked").value("138****5678"));
    }
}
