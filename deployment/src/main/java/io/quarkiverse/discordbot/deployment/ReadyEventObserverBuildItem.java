package io.quarkiverse.discordbot.deployment;

import io.quarkus.builder.item.MultiBuildItem;

public final class ReadyEventObserverBuildItem extends MultiBuildItem {
    private final String className;
    private final boolean function;

    public ReadyEventObserverBuildItem(String className, boolean function) {
        this.className = className;
        this.function = function;
    }

    public String getClassName() {
        return className;
    }

    public boolean isFunction() {
        return function;
    }
}
