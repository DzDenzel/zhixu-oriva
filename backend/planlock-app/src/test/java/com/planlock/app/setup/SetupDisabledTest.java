package com.planlock.app.setup;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(SetupEndpointTest.Config.class)
@TestPropertySource(properties="planlock.deployment.enabled=false")
class SetupDisabledTest {
    @Autowired WebApplicationContext context;
    @Test void sourceDeploymentDoesNotExposeSetup() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/setup")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/setup").contentType("application/json").content("{}")).andExpect(status().isNotFound());
    }
    @Test void defaultLoggingDoesNotPrintAccountMapperPasswordHashParameters() throws Exception {
        var source=new org.springframework.boot.env.YamlPropertySourceLoader().load("application",new org.springframework.core.io.ClassPathResource("application.yml")).getFirst();
        org.assertj.core.api.Assertions.assertThat(source.getProperty("logging.level.com.planlock.account.mapper")).isEqualTo("info");
    }
}
