package io.quarkiverse.discordbot.commands.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.quarkiverse.discordbot.commands.runtime.config.DiscordBotCommandsConfig;
import io.quarkiverse.discordbot.commands.runtime.config.GuildCommandsConfig;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.util.ClassPathUtils;

@Recorder
public class DiscordBotCommandsRecorder {

    public void registerCommands(DiscordBotCommandsConfig config) throws IOException {
        String globalCommandsPath = config.globalCommands.path;
        if (config.globalCommands.overwriteOnStart) {
            DiscordBotCommandsRegistrar.globalCommands = readCommands(globalCommandsPath);
        }

        for (Map.Entry<String, GuildCommandsConfig> entry : config.guildCommands.entrySet()) {
            String name = entry.getKey();
            GuildCommandsConfig commandsConfig = entry.getValue();

            if (commandsConfig.overwriteOnStart) {
                DiscordBotCommandsRegistrar.guildCommands.put(name,
                        readCommands(commandsConfig.path.orElse(globalCommandsPath + '/' + name)));
            }
        }
    }

    private static List<String> readCommands(String path) throws IOException {
        List<String> commands = new ArrayList<>();
        ClassPathUtils.consumeAsPaths(path, resource -> {
            try {
                Files.walkFileTree(resource, Collections.emptySet(), 1, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) throws IOException {
                        commands.add(Files.readString(p));
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return commands;
    }
}
