/*
 * Copyright (c) 2023. The BifroMQ Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.baidu.bifromq.dist.worker;

import static com.baidu.bifromq.dist.util.TopicUtil.escape;
import static com.baidu.bifromq.metrics.TenantMetric.MqttPersistentFanOutBytes;
import static com.baidu.bifromq.plugin.eventcollector.ThreadLocalEventPool.getLocal;
import static com.baidu.bifromq.sysprops.BifroMQSysProp.DIST_INLINE_FAN_OUT_THRESHOLD;
import static com.baidu.bifromq.sysprops.BifroMQSysProp.DIST_TOPIC_MATCH_EXPIRY;
import static com.bifromq.plugin.resourcethrottler.TenantResourceType.TotalPersistentFanOutBytesPerSeconds;
import static com.google.common.hash.Hashing.murmur3_128;

import com.baidu.bifromq.deliverer.IMessageDeliverer;
import com.baidu.bifromq.dist.client.IDistClient;
import com.baidu.bifromq.dist.entity.GroupMatching;
import com.baidu.bifromq.dist.entity.Matching;
import com.baidu.bifromq.dist.entity.NormalMatching;
import com.baidu.bifromq.metrics.ITenantMeter;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.ResourceThrottled;
import com.baidu.bifromq.type.ClientInfo;
import com.baidu.bifromq.type.TopicMessagePack;
import com.baidu.bifromq.util.SizeUtil;
import com.bifromq.plugin.resourcethrottler.IResourceThrottler;
import com.bifromq.plugin.resourcethrottler.TenantResourceType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Scheduler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class DeliverExecutorGroup {
    private record OrderedSharedMatchingKey(String tenantId, String escapedTopicFilter) {
    }

    // OuterCacheKey: OrderedSharedMatchingKey(<tenantId>, <escapedTopicFilter>)
    // InnerCacheKey: ClientInfo(<tenantId>, <type>, <metadata>)
    private final LoadingCache<OrderedSharedMatchingKey, Cache<ClientInfo, NormalMatching>> orderedSharedMatching;
    private final int inlineFanOutThreshold = DIST_INLINE_FAN_OUT_THRESHOLD.get();
    private final IEventCollector eventCollector;
    private final IResourceThrottler resourceThrottler;
    private final IDistClient distClient;
    private final IMessageDeliverer deliverer;
    private final DeliverExecutor[] fanoutExecutors;

    DeliverExecutorGroup(IMessageDeliverer deliverer,
                         IEventCollector eventCollector,
                         IResourceThrottler resourceThrottler,
                         IDistClient distClient,
                         int groupSize) {
        int expirySec = DIST_TOPIC_MATCH_EXPIRY.get();
        this.eventCollector = eventCollector;
        this.resourceThrottler = resourceThrottler;
        this.distClient = distClient;
        this.deliverer = deliverer;
        orderedSharedMatching = Caffeine.newBuilder()
            .expireAfterAccess(expirySec * 2L, TimeUnit.SECONDS)
            .scheduler(Scheduler.systemScheduler())
            .removalListener((RemovalListener<OrderedSharedMatchingKey, Cache<ClientInfo, NormalMatching>>)
                (key, value, cause) -> {
                    if (value != null) {
                        value.invalidateAll();
                    }
                })
            .build(k -> Caffeine.newBuilder()
                .expireAfterAccess(expirySec, TimeUnit.SECONDS)
                .build());
        fanoutExecutors = new DeliverExecutor[groupSize];
        for (int i = 0; i < groupSize; i++) {
            fanoutExecutors[i] = new DeliverExecutor(i, deliverer, eventCollector, distClient);
        }
    }

    public void shutdown() {
        for (DeliverExecutor fanoutExecutor : fanoutExecutors) {
            fanoutExecutor.shutdown();
        }
        orderedSharedMatching.invalidateAll();
    }

    public CompletableFuture<Boolean> submit(List<Matching> matchedRoutes, TopicMessagePack msgPack) {
        int msgPackSize = SizeUtil.estSizeOf(msgPack);
        if (matchedRoutes.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        if (matchedRoutes.size() == 1) {
            Matching matching = matchedRoutes.get(0);
            CompletableFuture<Boolean> onDone = prepareSend(matching, msgPack, true);
            if (isSendToInbox(matching)) {
                ITenantMeter.get(matching.tenantId).recordSummary(MqttPersistentFanOutBytes, msgPackSize);
            }
            return onDone;
        } else {
            String tenantId = matchedRoutes.get(0).tenantId;
            boolean inline = matchedRoutes.size() > inlineFanOutThreshold;
            boolean hasTFanOutBandwidth =
                resourceThrottler.hasResource(tenantId, TenantResourceType.TotalTransientFanOutBytesPerSeconds);
            boolean hasTFannedOutUnderThrottled = false;
            boolean hasPFanOutBandwidth =
                resourceThrottler.hasResource(tenantId, TenantResourceType.TotalPersistentFanOutBytesPerSeconds);
            boolean hasPFannedOutUnderThrottled = false;
            // we meter persistent fanout bytes here, since for transient fanout is actually happened in the broker
            long pFanoutBytes = 0;
            List<CompletableFuture<Boolean>> onDones = new ArrayList<>(matchedRoutes.size());
            for (Matching matching : matchedRoutes) {
                if (isSendToInbox(matching)) {
                    if (hasPFanOutBandwidth || !hasPFannedOutUnderThrottled) {
                        pFanoutBytes += msgPackSize;
                        onDones.add(prepareSend(matching, msgPack, inline));
                        if (!hasPFanOutBandwidth) {
                            hasPFannedOutUnderThrottled = true;
                            for (TopicMessagePack.PublisherPack publisherPack : msgPack.getMessageList()) {
                                eventCollector.report(getLocal(ResourceThrottled.class)
                                    .reason(TotalPersistentFanOutBytesPerSeconds.name())
                                    .clientInfo(publisherPack.getPublisher())
                                );
                            }
                        }
                    }
                } else if (hasTFanOutBandwidth || !hasTFannedOutUnderThrottled) {
                    onDones.add(prepareSend(matching, msgPack, inline));
                    if (!hasTFanOutBandwidth) {
                        hasTFannedOutUnderThrottled = true;
                        for (TopicMessagePack.PublisherPack publisherPack : msgPack.getMessageList()) {
                            eventCollector.report(getLocal(ResourceThrottled.class)
                                .reason(TenantResourceType.TotalTransientFanOutBytesPerSeconds.name())
                                .clientInfo(publisherPack.getPublisher())
                            );
                        }
                    }
                }
                if (hasPFannedOutUnderThrottled && hasTFannedOutUnderThrottled) {
                    break;
                }
            }
            ITenantMeter.get(tenantId).recordSummary(MqttPersistentFanOutBytes, pFanoutBytes);
            return CompletableFuture.allOf(onDones.toArray(CompletableFuture[]::new))
                .thenApply(v -> onDones.stream().allMatch(CompletableFuture::join));
        }
    }

    private boolean isSendToInbox(Matching matching) {
        return matching.type() == Matching.Type.Normal && ((NormalMatching) matching).subBrokerId == 1;
    }

    public void invalidate(ScopedTopic scopedTopic) {
        orderedSharedMatching.invalidate(new OrderedSharedMatchingKey(scopedTopic.tenantId, escape(scopedTopic.topic)));
    }

    private CompletableFuture<Boolean> prepareSend(Matching matching, TopicMessagePack msgPack, boolean inline) {
        return switch (matching.type()) {
            case Normal -> send((NormalMatching) matching, msgPack, inline);
            case Group -> {
                GroupMatching groupMatching = (GroupMatching) matching;
                if (!groupMatching.ordered) {
                    // pick one route randomly
                    yield send(groupMatching.receiverList.get(
                        ThreadLocalRandom.current().nextInt(groupMatching.receiverList.size())), msgPack, inline);
                } else {
                    // ordered shared subscription
                    Map<NormalMatching, TopicMessagePack.Builder> orderedRoutes = new HashMap<>();
                    for (TopicMessagePack.PublisherPack publisherPack : msgPack.getMessageList()) {
                        ClientInfo sender = publisherPack.getPublisher();
                        NormalMatching matchedInbox = orderedSharedMatching
                            .get(new OrderedSharedMatchingKey(groupMatching.tenantId, groupMatching.escapedTopicFilter))
                            .get(sender, k -> {
                                RendezvousHash<ClientInfo, NormalMatching> hash =
                                    new RendezvousHash<>(murmur3_128(),
                                        (from, into) -> into.putInt(from.hashCode()),
                                        (from, into) -> into.putBytes(from.scopedInboxId.getBytes()),
                                        Comparator.comparing(a -> a.scopedInboxId));
                                groupMatching.receiverList.forEach(hash::add);
                                return hash.get(k);
                            });
                        // ordered share sub
                        orderedRoutes.computeIfAbsent(matchedInbox, k -> TopicMessagePack.newBuilder())
                            .setTopic(msgPack.getTopic())
                            .addMessage(publisherPack);
                    }
                    List<CompletableFuture<Boolean>> onDones = new ArrayList<>(orderedRoutes.size());
                    orderedRoutes.forEach(
                        (route, msgPackBuilder) -> onDones.add(send(route, msgPackBuilder.build(), inline)));
                    yield CompletableFuture.allOf(onDones.toArray(CompletableFuture[]::new))
                        .thenApply(v -> onDones.stream().allMatch(CompletableFuture::join));
                }
            }
        };
    }

    private CompletableFuture<Boolean> send(NormalMatching route, TopicMessagePack msgPack, boolean inline) {
        int idx = route.hashCode() % fanoutExecutors.length;
        if (idx < 0) {
            idx += fanoutExecutors.length;
        }
        return fanoutExecutors[idx].submit(route, msgPack, inline);
    }
}
