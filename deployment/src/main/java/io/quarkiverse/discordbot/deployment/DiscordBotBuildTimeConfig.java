package io.quarkiverse.discordbot.deployment;

import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;

@ConfigRoot(name = "discord-bot", phase = ConfigPhase.BUILD_TIME)
public class DiscordBotBuildTimeConfig {
    /**
     * Whether a health check should be published for the Gateway clients managed by this extension if the
     * quarkus-smallrye-health extension is present
     */
    @ConfigItem(name = "health.enabled", defaultValue = "true")
    public boolean healthEnabled;

    /**
     * Whether metrics should be collected for the Gateway clients managed by this extension if the
     * quarkus-micrometer or quarkus-smallrye-metrics extension is present
     */
    @ConfigItem(name = "metrics.enabled")
    public boolean metricsEnabled;
}
