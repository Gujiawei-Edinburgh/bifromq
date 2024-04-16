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

import static com.baidu.bifromq.plugin.eventcollector.ThreadLocalEventPool.getLocal;
import static com.baidu.bifromq.plugin.subbroker.DeliveryResult.Code.ERROR;

import com.baidu.bifromq.baseenv.EnvProvider;
import com.baidu.bifromq.deliverer.DeliveryCall;
import com.baidu.bifromq.deliverer.IMessageDeliverer;
import com.baidu.bifromq.dist.client.IDistClient;
import com.baidu.bifromq.dist.entity.NormalMatching;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.baidu.bifromq.plugin.eventcollector.distservice.DeliverError;
import com.baidu.bifromq.plugin.eventcollector.distservice.DeliverNoInbox;
import com.baidu.bifromq.plugin.eventcollector.distservice.Delivered;
import com.baidu.bifromq.type.MatchInfo;
import com.baidu.bifromq.type.TopicMessagePack;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeliverExecutor {
    private final IEventCollector eventCollector;
    private final IDistClient distClient;
    private final IMessageDeliverer deliverer;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<SendTask> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean sending = new AtomicBoolean();

    public DeliverExecutor(int id,
                           IMessageDeliverer deliverer,
                           IEventCollector eventCollector,
                           IDistClient distClient) {
        this.eventCollector = eventCollector;
        this.distClient = distClient;
        this.deliverer = deliverer;
        executor = ExecutorServiceMetrics.monitor(Metrics.globalRegistry,
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedTransferQueue<>(),
                EnvProvider.INSTANCE.newThreadFactory("deliver-executor-" + id)), "deliver-executor-" + id);
    }

    public CompletableFuture<Boolean> submit(NormalMatching route, TopicMessagePack msgPack,
                                             boolean inline) {
        CompletableFuture<Boolean> onDone = new CompletableFuture<>();
        if (inline) {
            send(route, msgPack, onDone);
        } else {
            tasks.add(new SendTask(route, msgPack, onDone));
            scheduleSend();
        }
        return onDone;
    }

    public void shutdown() {
        executor.shutdown();
    }

    private void scheduleSend() {
        if (sending.compareAndSet(false, true)) {
            executor.submit(this::sendAll);
        }
    }

    private void sendAll() {
        SendTask task;
        while ((task = tasks.poll()) != null) {
            send(task.route, task.msgPack, task.onDone);
        }
        sending.set(false);
        if (!tasks.isEmpty()) {
            scheduleSend();
        }
    }

    private void send(NormalMatching matched, TopicMessagePack msgPack, CompletableFuture<Boolean> onDone) {
        int subBrokerId = matched.subBrokerId;
        String delivererKey = matched.delivererKey;
        MatchInfo sub = matched.matchInfo;
        DeliveryCall request = new DeliveryCall(matched.tenantId, sub, subBrokerId, delivererKey, msgPack);
        deliverer.schedule(request).whenComplete((result, e) -> {
            if (e != null) {
                log.debug("Failed to deliver", e);
                eventCollector.report(getLocal(DeliverError.class)
                    .brokerId(subBrokerId)
                    .delivererKey(delivererKey)
                    .subInfo(sub)
                    .messages(msgPack));
                onDone.complete(false);
            } else {
                switch (result) {
                    case OK -> eventCollector.report(getLocal(Delivered.class)
                        .brokerId(subBrokerId)
                        .delivererKey(delivererKey)
                        .subInfo(sub)
                        .messages(msgPack));
                    case NO_SUB, NO_RECEIVER -> {
                        // unsub as side effect
                        MatchInfo matchInfo = matched.matchInfo;
                        distClient.unmatch(System.nanoTime(),
                            matched.tenantId,
                            matchInfo.getTopicFilter(),
                            matchInfo.getReceiverId(),
                            delivererKey,
                            subBrokerId);
                        eventCollector.report(getLocal(DeliverNoInbox.class)
                            .brokerId(subBrokerId)
                            .delivererKey(delivererKey)
                            .subInfo(sub)
                            .messages(msgPack));
                    }
                    case ERROR -> eventCollector.report(getLocal(DeliverError.class)
                        .brokerId(subBrokerId)
                        .delivererKey(delivererKey)
                        .subInfo(sub)
                        .messages(msgPack));
                }
                onDone.complete(result != ERROR);
            }
        });
    }

    private record SendTask(NormalMatching route, TopicMessagePack msgPack, CompletableFuture<Boolean> onDone) {
    }
}
