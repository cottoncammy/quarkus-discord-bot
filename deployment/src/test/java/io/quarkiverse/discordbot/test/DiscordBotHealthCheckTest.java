package io.quarkiverse.discordbot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.enterprise.inject.Any;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.discordbot.runtime.health.DiscordBotHealthCheck;
import io.quarkus.test.QuarkusUnitTest;

public class DiscordBotHealthCheckTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withConfigurationResource("application.properties");

    @Any
    DiscordBotHealthCheck health;

    @Test
    public void testHealthCheck() {
        HealthCheckResponse response = health.call().await().indefinitely();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals(DiscordBotHealthCheck.NAME, response.getName());
    }
}
