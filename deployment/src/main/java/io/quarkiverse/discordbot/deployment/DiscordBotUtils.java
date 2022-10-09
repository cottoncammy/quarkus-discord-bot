package io.quarkiverse.discordbot.deployment;

import static io.quarkiverse.discordbot.deployment.DiscordBotDotNames.*;
import static io.quarkiverse.discordbot.deployment.DiscordBotMethodDescriptors.*;

import java.util.List;

import org.jboss.jandex.DotName;
import org.jboss.jandex.Type;

import io.quarkus.arc.processor.MethodDescriptors;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.ResultHandle;

public class DiscordBotUtils {
    private static final List<DotName> RETURN_TYPES = List.of(FLUX, MONO, MULTI, UNI);

    public static ResultHandle convertIfUni(String typeName, BytecodeCreator bc, ResultHandle resultHandle) {
        if (typeName.equals(UNI.toString())) {
            resultHandle = bc.invokeVirtualMethod(TO_MONO_APPLY, bc.invokeStaticMethod(UNI_REACTOR_CONVERTERS_TO_MONO),
                    resultHandle);
        }
        return resultHandle;
    }

    public static ResultHandle getBeanInstance(BytecodeCreator bc, String className) {
        return bc.invokeInterfaceMethod(INSTANCE_HANDLE_GET,
                bc.invokeInterfaceMethod(ARC_CONTAINER_INSTANCE,
                        bc.invokeStaticMethod(MethodDescriptors.ARC_CONTAINER),
                        bc.loadClass(className), bc.loadNull()));
    }

    public static boolean isValidReturnType(Type type) {
        return RETURN_TYPES.contains(type.name());
    }

    private DiscordBotUtils() {
    }
}
