package io.quarkiverse.discordbot.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.enterprise.context.spi.CreationalContext;

import org.reactivestreams.Publisher;

import discord4j.common.ReactorResources;
import discord4j.common.store.Store;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.EventDispatcher;
import discord4j.core.event.dispatch.DispatchContext;
import discord4j.core.event.dispatch.DispatchEventMapper;
import discord4j.core.event.domain.Event;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.object.presence.ClientActivity;
import discord4j.core.object.presence.ClientPresence;
import discord4j.core.object.presence.Status;
import discord4j.core.retriever.EntityRetrievalStrategy;
import discord4j.core.shard.DefaultShardingStrategy;
import discord4j.core.shard.GatewayBootstrap;
import discord4j.core.shard.ShardingStrategy;
import discord4j.discordjson.json.gateway.Ready;
import discord4j.discordjson.json.gateway.Resumed;
import discord4j.gateway.GatewayOptions;
import discord4j.gateway.intent.IntentSet;
import discord4j.gateway.retry.GatewayStateChange;
import io.quarkiverse.discordbot.runtime.config.DiscordBotConfig;
import io.quarkiverse.discordbot.runtime.config.PresenceConfig;
import io.quarkiverse.discordbot.runtime.metrics.MicroProfileGatewayClientMetricsHandler;
import io.quarkiverse.discordbot.runtime.metrics.MicrometerGatewayClientMetricsHandler;
import io.quarkus.arc.Arc;
import io.quarkus.arc.BeanDestroyer;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.metrics.MetricsFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

@Recorder
public class DiscordBotRecorder {
    private static volatile Mono<Event> lastEvent;
    public static volatile Supplier<CompletableFuture<Boolean>> hotReplacementHandler;

    private static Function<EventDispatcher, Publisher<?>> metricsHandler;
    private static List<Function<ReadyEvent, Publisher<?>>> readyEventFunctions = new ArrayList<>();
    private static List<Consumer<ReadyEvent>> readyEventConsumers = new ArrayList<>();

    private static ClientPresence getPresence(PresenceConfig presenceConfig) {
        return ClientPresence.of(presenceConfig.status.orElse(Status.ONLINE), presenceConfig.activity
                .map(activity -> ClientActivity.of(activity.type, activity.name, activity.url.orElse(null)))
                .orElse(null));
    }

    private static void setEntityRetrievalStrategy(GatewayBootstrap<GatewayOptions> gatewayBootstrap,
            EntityRetrievalStrategy strategy) {
        gatewayBootstrap.setEntityRetrievalStrategy(strategy);
        if (strategy == EntityRetrievalStrategy.REST) {
            gatewayBootstrap.setStore(Store.noOp());
        }
    }

    private static Object getBeanInstance(String className) {
        try {
            Class<?> cl = Thread.currentThread().getContextClassLoader().loadClass(className);
            return Arc.container().instance(cl).get();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Function<ReadyEvent, Publisher<?>> getReadyEventFunction(String className) {
        return (Function<ReadyEvent, Publisher<?>>) getBeanInstance(className);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<ReadyEvent> getReadyEventConsumer(String className) {
        return (Consumer<ReadyEvent>) getBeanInstance(className);
    }

    public void setupMetrics(String type) {
        if (type.equals(MetricsFactory.MICROMETER)) {
            metricsHandler = new MicrometerGatewayClientMetricsHandler();
        } else {
            metricsHandler = new MicroProfileGatewayClientMetricsHandler();
        }
    }

    public void setReadyEventFunctions(List<String> classNames) {
        readyEventFunctions = classNames.stream().map(DiscordBotRecorder::getReadyEventFunction).collect(Collectors.toList());
    }

    public void setReadyEventConsumers(List<String> classNames) {
        readyEventConsumers = classNames.stream().map(DiscordBotRecorder::getReadyEventConsumer).collect(Collectors.toList());
    }

    // TODO configure schedulers, event loops
    // TODO jackson object mapper?
    public Supplier<DiscordClient> createDiscordClient(DiscordBotConfig config, boolean ssl, ExecutorService executorService) {
        return new Supplier<DiscordClient>() {
            @Override
            public DiscordClient get() {
                HttpClient httpClient = HttpClient.create().compress(true).followRedirect(true);
                return DiscordClient.builder(config.token)
                        .setReactorResources(ReactorResources.builder()
                                .httpClient(ssl ? httpClient.secure() : httpClient)
                                .blockingTaskScheduler(Schedulers.fromExecutorService(executorService))
                                .build())
                        .build();
            }
        };
    }

    public Supplier<GatewayDiscordClient> createGatewayClient(DiscordBotConfig config,
            Supplier<DiscordClient> discordClientSupplier) {
        return new Supplier<GatewayDiscordClient>() {
            @Override
            public GatewayDiscordClient get() {
                GatewayBootstrap<GatewayOptions> bootstrap = discordClientSupplier.get().gateway();
                bootstrap.setInitialPresence(shard -> getPresence(config.presence));
                config.enabledIntents.ifPresent(intents -> bootstrap.setEnabledIntents(IntentSet.of(intents)));
                config.entityRetrievalStrategy.ifPresent(strategy -> setEntityRetrievalStrategy(bootstrap, strategy));

                DefaultShardingStrategy.Builder shardBuilder = ShardingStrategy.builder();
                config.sharding.count.ifPresent(shardBuilder::count);
                config.sharding.indices.ifPresent(shardBuilder::indices);
                config.sharding.maxConcurrency.ifPresent(shardBuilder::maxConcurrency);
                bootstrap.setSharding(shardBuilder.build());

                if (hotReplacementHandler != null) {
                    DispatchEventMapper emitter = DispatchEventMapper.emitEvents();
                    bootstrap.setDispatchEventMapper(new DispatchEventMapper() {
                        @Override
                        @SuppressWarnings("unchecked")
                        public <D, S, E extends Event> Mono<E> handle(DispatchContext<D, S> context) {
                            Class<?> dispatch = context.getDispatch().getClass();
                            if (Ready.class.isAssignableFrom(dispatch) || Resumed.class.isAssignableFrom(dispatch) ||
                                    GatewayStateChange.class.isAssignableFrom(dispatch)) {
                                return emitter.handle(context);
                            }

                            Mono<E> event = emitter.handle(context);
                            lastEvent = (Mono<Event>) event;

                            return Mono.fromFuture(hotReplacementHandler.get())
                                    .flatMap(restarted -> restarted ? Mono.empty() : event);
                        }
                    });
                }

                bootstrap.withEventDispatcher(dispatcher -> {
                    List<Publisher<?>> sources = new ArrayList<>();
                    if (lastEvent != null) {
                        sources.add(
                                dispatcher.on(ReadyEvent.class).flatMap(ignored -> lastEvent).doOnNext(dispatcher::publish));
                    }

                    if (metricsHandler != null) {
                        sources.add(metricsHandler.apply(dispatcher));
                    }

                    for (Function<ReadyEvent, Publisher<?>> readyEventFunction : readyEventFunctions) {
                        sources.add(dispatcher.on(ReadyEvent.class).flatMap(readyEventFunction));
                    }

                    for (Consumer<ReadyEvent> readyEventConsumer : readyEventConsumers) {
                        sources.add(dispatcher.on(ReadyEvent.class).doOnNext(readyEventConsumer));
                    }
                    return Flux.concat(sources);
                });

                return bootstrap.login().block();
            }
        };
    }

    public static class GatewayClientDestroyer implements BeanDestroyer<GatewayDiscordClient> {

        @Override
        public void destroy(GatewayDiscordClient instance, CreationalContext<GatewayDiscordClient> context,
                Map<String, Object> params) {
            instance.logout().block();
        }
    }
}
