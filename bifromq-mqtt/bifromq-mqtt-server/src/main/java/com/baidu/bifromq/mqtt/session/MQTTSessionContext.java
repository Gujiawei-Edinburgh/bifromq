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

package com.baidu.bifromq.mqtt.session;

import static com.baidu.bifromq.metrics.TenantMetric.MqttSessionWorkingMemoryGauge;
import static com.baidu.bifromq.metrics.TenantMetric.MqttTransientSubCountGauge;

import com.baidu.bifromq.baserpc.utils.FutureTracker;
import com.baidu.bifromq.dist.client.IDistClient;
import com.baidu.bifromq.inbox.client.IInboxClient;
import com.baidu.bifromq.mqtt.service.ILocalDistService;
import com.baidu.bifromq.mqtt.service.ILocalSessionRegistry;
import com.baidu.bifromq.plugin.authprovider.IAuthProvider;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.baidu.bifromq.plugin.settingprovider.ISettingProvider;
import com.baidu.bifromq.retain.client.IRetainClient;
import com.baidu.bifromq.sessiondict.client.ISessionDictClient;
import com.bifromq.plugin.resourcethrottler.IResourceThrottler;
import io.netty.channel.ChannelHandlerContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class MQTTSessionContext {
    private final IAuthProvider authProvider;
    public final ILocalSessionRegistry localSessionRegistry;
    public final ILocalDistService localDistService;
    public final IEventCollector eventCollector;
    public final IResourceThrottler resourceThrottler;
    public final ISettingProvider settingProvider;
    public final IDistClient distClient;
    public final IInboxClient inboxClient;
    public final IRetainClient retainClient;
    public final ISessionDictClient sessionDictClient;
    public final String serverId;
    public final int defaultKeepAliveTimeSeconds;
    private final ITicker ticker;
    private final FutureTracker futureTracker = new FutureTracker();
    private final TenantGauge tenantTransientSubNumGauge;
    private final TenantGauge tenantMemGauge;

    @Builder
    MQTTSessionContext(String serverId,
                       ILocalSessionRegistry localSessionRegistry,
                       ILocalDistService localDistService,
                       IAuthProvider authProvider,
                       IDistClient distClient,
                       IInboxClient inboxClient,
                       IRetainClient retainClient,
                       ISessionDictClient sessionDictClient,
                       int defaultKeepAliveTimeSeconds,
                       IEventCollector eventCollector,
                       IResourceThrottler resourceThrottler,
                       ISettingProvider settingProvider,
                       ITicker ticker) {
        this.serverId = serverId;
        this.localSessionRegistry = localSessionRegistry;
        this.localDistService = localDistService;
        this.authProvider = authProvider;
        this.eventCollector = eventCollector;
        this.resourceThrottler = resourceThrottler;
        this.settingProvider = settingProvider;
        this.distClient = distClient;
        this.inboxClient = inboxClient;
        this.retainClient = retainClient;
        this.sessionDictClient = sessionDictClient;
        this.defaultKeepAliveTimeSeconds = defaultKeepAliveTimeSeconds;
        this.ticker = ticker == null ? ITicker.SYSTEM_TICKER : ticker;
        this.tenantTransientSubNumGauge = new TenantGauge(MqttTransientSubCountGauge);
        this.tenantMemGauge = new TenantGauge(MqttSessionWorkingMemoryGauge);
    }

    public long nanoTime() {
        return ticker.systemNanos();
    }

    public long nowMillis() {
        return ticker.nowMillis();
    }

    public IAuthProvider authProvider(ChannelHandlerContext ctx) {
        // a wrapper to ensure async fifo semantic for check call
        return new MQTTSessionAuthProvider(authProvider, ctx);
    }

    public AtomicLong getTransientSubNumGauge(String tenantId) {
        return tenantTransientSubNumGauge.get(tenantId);
    }

    public AtomicLong getSessionMemGauge(String tenantId) {
        return tenantMemGauge.get(tenantId);
    }

    public <T> CompletableFuture<T> trackBgTask(CompletableFuture<T> task) {
        return futureTracker.track(task);
    }

    public CompletableFuture<Void> awaitBgTasksFinish() {
        CompletableFuture<Void> onDone = new CompletableFuture<>();
        futureTracker.whenComplete((v, e) -> onDone.complete(null));
        return onDone;
    }
}
