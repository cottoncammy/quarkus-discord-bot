package io.quarkiverse.discordbot.commands.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.Interaction;
import discord4j.discordjson.json.InteractionData;
import discord4j.gateway.ShardInfo;
import io.quarkiverse.discordbot.commands.Command;
import io.quarkiverse.discordbot.commands.SubCommand;
import io.quarkiverse.discordbot.commands.SubCommandGroup;
import io.quarkus.test.QuarkusUnitTest;

public class DiscordBotCommandsTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(FooCommand.class, FooCommand.BarCommand.class)
                    .addAsResource("META-INF/commands/test/foo.json")
                    .addAsResource("interaction-create.json"))
            .setLogRecordPredicate(lr -> lr.getLoggerName().equals(FooCommand.BarCommand.class.getName()))
            .withConfigurationResource("application.properties");

    @Inject
    GatewayDiscordClient gateway;

    @ConfigProperty(name = "quarkus.discord-bot.guild-commands.test.guild-id")
    long guildId;

    @Inject
    ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        DiscordClient rest = gateway.rest();
        rest.getApplicationId().flatMap(id -> rest.getApplicationService()
                .getGuildApplicationCommands(id, guildId)
                .filter(command -> command.name().equals("foo"))
                .flatMap(command -> rest.getApplicationService()
                        .deleteGuildApplicationCommand(id, guildId, Long.parseLong(command.id())))
                .then())
                .block();

        if (gateway != null) {
            gateway.logout().block();
        }
    }

    @Test
    public void commandTest() throws IOException {
        InteractionData data = objectMapper.readValue(
                Thread.currentThread().getContextClassLoader().getResource("interaction-create.json"),
                InteractionData.class);
        data = InteractionData.builder().from(data).guildId(String.format("%d", guildId)).build();
        gateway.getEventDispatcher()
                .publish(new ChatInputInteractionEvent(gateway, ShardInfo.create(0, 1), new Interaction(gateway, data)));

        config.assertLogRecords(lr -> assertEquals(1, lr.size()));
    }

    @Command(value = "foo", guild = "test")
    static class FooCommand {

        @SubCommandGroup("bar")
        static class BarCommand {
            private static final Logger LOGGER = Logger.getLogger(BarCommand.class);

            @SubCommand("baz")
            void onChatInputInteraction(ChatInputInteractionEvent chatInputInteraction) {
                LOGGER.info("Received ChatInputInteractionEvent");
            }
        }
    }
}
