package io.quarkiverse.discordbot.deployment.spi;

import java.util.function.Function;

import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;

public final class GatewayEventSubscriberReactorOperatorBuildItem extends MultiBuildItem {
    private final String eventClassName;
    private final MethodDescriptor operator;
    private final Function<MethodCreator, ResultHandle> operatorArgCreator;

    public GatewayEventSubscriberReactorOperatorBuildItem(String eventClassName, MethodDescriptor operator,
            Function<MethodCreator, ResultHandle> operatorArgCreator) {
        this.eventClassName = eventClassName;
        this.operator = operator;
        this.operatorArgCreator = operatorArgCreator;
    }

    public String getEventClassName() {
        return eventClassName;
    }

    public MethodDescriptor getOperator() {
        return operator;
    }

    public Function<MethodCreator, ResultHandle> getOperatorArgCreator() {
        return operatorArgCreator;
    }
}
