package io.quarkiverse.discordbot.deployment;

import static io.quarkiverse.discordbot.deployment.DiscordBotDotNames.*;
import static io.quarkiverse.discordbot.deployment.DiscordBotMethodDescriptors.*;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;

import org.jboss.jandex.*;
import org.reactivestreams.Publisher;

import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.discordjson.possible.PossibleModule;
import io.netty.channel.EventLoopGroup;
import io.quarkiverse.discordbot.deployment.spi.GatewayEventSubscriberReactorOperatorBuildItem;
import io.quarkiverse.discordbot.runtime.DiscordBotRecorder;
import io.quarkiverse.discordbot.runtime.config.DiscordBotConfig;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.deployment.*;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.*;
import io.quarkus.deployment.builditem.nativeimage.NativeImageConfigBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.metrics.MetricsCapabilityBuildItem;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;
import io.quarkus.gizmo.*;
import io.quarkus.jackson.spi.ClassPathJacksonModuleBuildItem;
import io.quarkus.netty.deployment.EventLoopSupplierBuildItem;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.metrics.MetricsFactory;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;

public class DiscordBotProcessor {
    private static final String FEATURE = "discord-bot";
    private static final String PACKAGE = "io.quarkiverse.discordbot.runtime.";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    ExtensionSslNativeSupportBuildItem nativeSsl() {
        return new ExtensionSslNativeSupportBuildItem(FEATURE);
    }

    @BuildStep
    IndexDependencyBuildItem indexDep() {
        return new IndexDependencyBuildItem("com.discord4j", "discord4j-core");
    }

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    List<IndexDependencyBuildItem> indexDeps() {
        // index deps so the jackson extension can discover classes to register for reflection
        return List.of(
                new IndexDependencyBuildItem("com.discord4j", "discord4j-gateway"),
                new IndexDependencyBuildItem("com.discord4j", "discord4j-voice"),
                new IndexDependencyBuildItem("com.discord4j", "discord-json"),
                new IndexDependencyBuildItem("com.discord4j", "discord-json-api"));
    }

    @BuildStep
    void reflection(BuildProducer<ReflectiveClassBuildItem> reflection) {
        // jackson classes that the extension doesn't discover
        reflection.produce(new ReflectiveClassBuildItem(false, false,
                "com.fasterxml.jackson.databind.ser.std.ClassSerializer",
                "discord4j.discordjson.json.gateway.HeartbeatConverter",
                "discord4j.discordjson.json.gateway.OpcodeConverter",
                "discord4j.discordjson.possible.PossibleFilter",
                "discord4j.rest.json.response.ErrorResponse"));

        // caffeine deps not covered by the caffeine extension
        reflection.produce(new ReflectiveClassBuildItem(true, true,
                "com.github.benmanes.caffeine.cache.PW",
                "com.github.benmanes.caffeine.cache.SI"));
    }

    @BuildStep
    NativeImageConfigBuildItem nativeImg() {
        return NativeImageConfigBuildItem.builder()
                .addRuntimeInitializedClass("reactor.netty.http.client.HttpClientFormEncoder")
                .addRuntimeInitializedClass("reactor.netty.resources.DefaultLoopEpoll")
                .addRuntimeInitializedClass("reactor.netty.resources.DefaultLoopNativeDetector")
                .addRuntimeInitializedClass(PACKAGE + "graal.HttpClientSecureSubstitution$DefaultHttpSslProviderLazyHolder")
                .addRuntimeInitializedClass(PACKAGE + "graal.HttpClientSecureSubstitution$DefaultHttp2SslProviderLazyHolder")
                .addRuntimeInitializedClass(PACKAGE + "graal.TcpClientSecureSubstitution$DefaultSslProviderLazyHolder")
                .build();
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void metrics(DiscordBotBuildTimeConfig config, Optional<MetricsCapabilityBuildItem> metrics,
            DiscordBotRecorder recorder) {
        if (config.metricsEnabled && metrics.isPresent()) {
            recorder.setupMetrics(metrics.get().metricsSupported(MetricsFactory.MICROMETER)
                    ? MetricsFactory.MICROMETER
                    : MetricsFactory.MP_METRICS);
        }
    }

    @BuildStep
    HealthBuildItem health(DiscordBotBuildTimeConfig config) {
        return new HealthBuildItem(PACKAGE + "health.DiscordBotHealthCheck", config.healthEnabled);
    }

    @BuildStep
    AutoAddScopeBuildItem autoAddScope() {
        return AutoAddScopeBuildItem.builder()
                .containsAnnotations(GATEWAY_EVENT)
                .defaultScope(BuiltinScope.APPLICATION)
                .build();
    }

    @BuildStep
    ClassPathJacksonModuleBuildItem jackson() {
        return new ClassPathJacksonModuleBuildItem(PossibleModule.class.getName());
    }

    @BuildStep
    void observers(CombinedIndexBuildItem combinedIndex, BuildProducer<GatewayEventObserverBuildItem> observers) {
        IndexView indexView = combinedIndex.getIndex();
        Set<DotName> eventClassNames = eventClassNames(indexView);

        for (AnnotationInstance annotation : indexView.getAnnotations(GATEWAY_EVENT)) {
            MethodParameterInfo param = annotation.target().asMethodParameter();
            MethodInfo method = param.method();
            DotName className = method.declaringClass().name();
            Type returnType = method.returnType();
            DotName paramName = param.method().parameterTypes().get(param.position()).name();

            // the observed type must be a gateway event class
            if (!eventClassNames.contains(paramName)) {
                throw new IllegalStateException(String.format(
                        "Parameter %s annotated with %s at %s:%s must observe a Gateway event class", paramName,
                        GATEWAY_EVENT, className, method.name()));
            }

            // the first parameter must be the observed type
            if (param.position() != 0) {
                throw new IllegalStateException(String.format(
                        "The first parameter of the Gateway event observer method at %s:%s must be the observed Gateway event",
                        className, method.name()));
            }

            // return type must be void, Uni, Mono, Flux, or Multi
            if (!DiscordBotUtils.isValidReturnType(returnType)) {
                throw new IllegalStateException(String.format(
                        "Gateway event observer method at %s:%s must return void, %s, %s, %s, or %s",
                        className, method.name(), MONO, UNI, FLUX, MULTI));
            }

            List<Type> params = new ArrayList<>(method.parameterTypes());
            params.remove(0);

            observers.produce(new GatewayEventObserverBuildItem(
                    className, returnType.name().toString(), method.name(), paramName.toString(), params));
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void syntheticBeans(DiscordBotRecorder recorder, DiscordBotConfig config, SslNativeConfigBuildItem nativeSslConfig,
            ExecutorBuildItem executor, Optional<EventLoopSupplierBuildItem> eventLoopSupplierBuildItem,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
        Supplier<EventLoopGroup> eventLoopGroupSupplier;
        if (eventLoopSupplierBuildItem.isPresent()) {
            eventLoopGroupSupplier = eventLoopSupplierBuildItem.get().getMainSupplier();
        } else {
            eventLoopGroupSupplier = recorder.getEventLoopGroupBean();
        }

        Supplier<DiscordClient> discordClient = recorder.createDiscordClient(config, nativeSslConfig.isEnabled(),
                executor.getExecutorProxy(), eventLoopGroupSupplier);
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(GatewayDiscordClient.class)
                .scope(ApplicationScoped.class)
                .addQualifier(Default.class)
                .supplier(recorder.createGatewayClient(config, discordClient))
                .destroyer(DiscordBotRecorder.GatewayDiscordClientDestroyer.class)
                .setRuntimeInit()
                .unremovable()
                .done());
    }

    @BuildStep
    void subscriberOperators(List<GatewayEventObserverBuildItem> observers,
            BuildProducer<GeneratedBeanBuildItem> generatedBeans,
            BuildProducer<ReadyEventObserverBuildItem> readyEvents,
            BuildProducer<GatewayEventSubscriberReactorOperatorBuildItem> subscriberOperators) {
        ClassOutput output = new GeneratedBeanGizmoAdaptor(generatedBeans);
        Map<GatewayEventObserverBuildItem, String> proxyClasses = observerProxies(observers, output);

        observers.removeIf(observer -> {
            if (observer.observesReadyEvent()) {
                readyEvents.produce(new ReadyEventObserverBuildItem(proxyClasses.get(observer), !observer.returnsVoid()));
            }
            return observer.observesReadyEvent();
        });

        for (GatewayEventObserverBuildItem observer : observers) {
            subscriberOperators.produce(new GatewayEventSubscriberReactorOperatorBuildItem(
                    observer.getEventClassName(),
                    observer.returnsVoid() ? FLUX_DO_ON_NEXT : FLUX_FLAT_MAP,
                    mc -> DiscordBotUtils.getBeanInstance(mc, proxyClasses.get(observer))));
        }
    }

    @BuildStep
    void eventSubscriber(List<GatewayEventSubscriberReactorOperatorBuildItem> subscriberOperators,
            BuildProducer<GeneratedBeanBuildItem> generatedBeans) {
        if (subscriberOperators.isEmpty()) {
            return;
        }

        ClassOutput output = new GeneratedBeanGizmoAdaptor(generatedBeans);
        ClassCreator cc = ClassCreator.builder().classOutput(output).className(PACKAGE + "GatewayEventSubscriber").build();
        cc.addAnnotation(ApplicationScoped.class);

        MethodCreator mc = cc.getMethodCreator("onStartup", void.class, StartupEvent.class, GatewayDiscordClient.class);
        mc.getParameterAnnotations(0).addAnnotation(DotNames.OBSERVES.toString());

        ResultHandle publishers = mc.newArray(Publisher.class, subscriberOperators.size());
        for (int i = 0; i < subscriberOperators.size(); i++) {
            GatewayEventSubscriberReactorOperatorBuildItem operator = subscriberOperators.get(i);

            ResultHandle flux = mc.invokeVirtualMethod(GATEWAY_DISCORD_CLIENT_ON, mc.getMethodParam(1),
                    mc.loadClass(operator.getEventClassName()));
            flux = mc.invokeVirtualMethod(operator.getOperator(), flux, operator.getOperatorArgCreator().apply(mc));
            mc.writeArrayValue(publishers, i, mc.invokeVirtualMethod(FLUX_THEN, flux));
        }

        mc.invokeVirtualMethod(MONO_SUBSCRIBE, mc.invokeStaticMethod(MONO_WHEN, publishers));

        mc.returnValue(mc.loadNull());
        cc.close();
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void readyEvents(DiscordBotRecorder recorder, BeanContainerBuildItem arc,
            List<ReadyEventObserverBuildItem> readyEventObservers) {
        Map<Boolean, List<ReadyEventObserverBuildItem>> observers = readyEventObservers.stream()
                .collect(Collectors.partitioningBy(ReadyEventObserverBuildItem::isFunction));

        recorder.setReadyEventFunctions(observers.get(true).stream()
                .map(ReadyEventObserverBuildItem::getClassName)
                .collect(Collectors.toList()));
        recorder.setReadyEventConsumers(observers.get(false).stream()
                .map(ReadyEventObserverBuildItem::getClassName)
                .collect(Collectors.toList()));
    }

    private static Set<DotName> eventClassNames(IndexView indexView) {
        return indexView.getAllKnownSubclasses(EVENT).stream()
                .filter(cl -> !Modifier.isAbstract(cl.flags()))
                .map(ClassInfo::name)
                .collect(Collectors.toSet());
    }

    private static Map<GatewayEventObserverBuildItem, String> observerProxies(List<GatewayEventObserverBuildItem> observers,
            ClassOutput output) {
        Map<GatewayEventObserverBuildItem, String> classNames = new HashMap<>();
        for (int i = 0; i < observers.size(); i++) {
            GatewayEventObserverBuildItem observer = observers.get(i);
            String className = observer.getClassName().packagePrefix() + ".GatewayEventObserverProxy" + (i + 1);
            classNames.put(observer, className);

            String observerClass = observer.getClassName().toString();
            String eventClass = observer.getEventClassName();
            List<String> params = observer.getInjectionPoints().stream()
                    .map(Type::name)
                    .map(DotName::toString)
                    .collect(Collectors.toList());

            ClassCreator cc = ClassCreator.builder()
                    .classOutput(output)
                    .className(className)
                    .interfaces(observer.returnsVoid() ? Consumer.class : Function.class)
                    .build();

            cc.addAnnotation(ApplicationScoped.class);
            cc.addAnnotation(Unremovable.class);

            MethodCreator mc = cc.getMethodCreator(observer.returnsVoid() ? CONSUMER_ACCEPT : FUNCTION_APPLY);
            mc.setModifiers(Modifier.PUBLIC);

            FieldCreator instance = cc.getFieldCreator("observer", observerClass);
            instance.setModifiers(Modifier.PROTECTED);
            instance.addAnnotation(DotNames.INJECT.toString());

            List<ResultHandle> resultHandles = new ArrayList<>();
            resultHandles.add(mc.checkCast(mc.getMethodParam(0), eventClass));
            for (int j = 0; j < observer.getInjectionPoints().size(); j++) {
                Type injectionPoint = observer.getInjectionPoints().get(j);
                FieldCreator field = cc.getFieldCreator("$" + j, injectionPoint.name().toString());
                field.setModifiers(Modifier.PROTECTED);
                field.addAnnotation(DotNames.INJECT.toString());
                injectionPoint.annotations().forEach(field::addAnnotation);
                resultHandles.add(mc.readInstanceField(field.getFieldDescriptor(), mc.getThis()));
            }

            params.add(0, eventClass);
            ResultHandle publisher = mc.invokeVirtualMethod(
                    MethodDescriptor.ofMethod(
                            observerClass, observer.getMethod(), observer.getReturnType(), params.toArray(new String[0])),
                    mc.readInstanceField(instance.getFieldDescriptor(), mc.getThis()),
                    resultHandles.toArray(new ResultHandle[0]));

            mc.returnValue(DiscordBotUtils.convertIfUni(observer.getReturnType(), mc, publisher));
            cc.close();
        }

        return classNames;
    }
}
