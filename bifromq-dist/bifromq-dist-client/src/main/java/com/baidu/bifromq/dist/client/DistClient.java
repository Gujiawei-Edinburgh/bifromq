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

package com.baidu.bifromq.dist.client;

import com.baidu.bifromq.baserpc.IRPCClient;
import com.baidu.bifromq.basescheduler.exception.BackPressureException;
import com.baidu.bifromq.dist.RPCBluePrint;
import com.baidu.bifromq.dist.client.scheduler.DistServerCall;
import com.baidu.bifromq.dist.client.scheduler.DistServerCallScheduler;
import com.baidu.bifromq.dist.client.scheduler.IDistServerCallScheduler;
import com.baidu.bifromq.dist.rpc.proto.DistServiceGrpc;
import com.baidu.bifromq.dist.rpc.proto.MatchRequest;
import com.baidu.bifromq.dist.rpc.proto.UnmatchRequest;
import com.baidu.bifromq.type.ClientInfo;
import com.baidu.bifromq.type.Message;
import io.reactivex.rxjava3.core.Observable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class DistClient implements IDistClient {
    private final IDistServerCallScheduler reqScheduler;
    private final IRPCClient rpcClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    DistClient(DistClientBuilder builder) {
        this.rpcClient = IRPCClient.newBuilder()
            .bluePrint(RPCBluePrint.INSTANCE)
            .executor(builder.executor)
            .eventLoopGroup(builder.eventLoopGroup)
            .crdtService(builder.crdtService)
            .sslContext(builder.sslContext)
            .build();
        reqScheduler = new DistServerCallScheduler(rpcClient);
    }

    @Override
    public Observable<IRPCClient.ConnState> connState() {
        return rpcClient.connState();
    }

    @Override
    public CompletableFuture<DistResult> pub(long reqId, String topic, Message message, ClientInfo publisher) {
        return reqScheduler.schedule(new DistServerCall(publisher, topic, message))
            .exceptionally(e -> {
                log.debug("Failed to pub", e);
                if (e instanceof BackPressureException || e.getCause() instanceof BackPressureException) {
                    return DistResult.BACK_PRESSURE_REJECTED;
                } else {
                    return DistResult.ERROR;
                }
            });
    }

    @Override
    public CompletableFuture<MatchResult> match(long reqId,
                                                String tenantId,
                                                String topicFilter,
                                                String receiverId,
                                                String delivererKey,
                                                int subBrokerId) {
        MatchRequest request = MatchRequest.newBuilder()
            .setReqId(reqId)
            .setTenantId(tenantId)
            .setTopicFilter(topicFilter)
            .setReceiverId(receiverId)
            .setDelivererKey(delivererKey)
            .setBrokerId(subBrokerId)
            .build();
        log.trace("Handling match request:\n{}", request);
        return rpcClient.invoke(tenantId, null, request, DistServiceGrpc.getMatchMethod())
            .thenApply(v -> MatchResult.values()[v.getResult().getNumber()])
            .exceptionally(e -> {
                log.debug("Failed to match", e);
                return MatchResult.ERROR;
            });
    }

    @Override
    public CompletableFuture<UnmatchResult> unmatch(long reqId, String tenantId, String topicFilter, String receiverId,
                                                    String delivererKey, int subBrokerId) {
        UnmatchRequest request = UnmatchRequest.newBuilder()
            .setReqId(reqId)
            .setTenantId(tenantId)
            .setTopicFilter(topicFilter)
            .setReceiverId(receiverId)
            .setDelivererKey(delivererKey)
            .setBrokerId(subBrokerId)
            .build();
        log.trace("Handling unsub request:\n{}", request);
        return rpcClient.invoke(tenantId, null, request, DistServiceGrpc.getUnmatchMethod())
            .thenApply(v -> UnmatchResult.values()[v.getResult().getNumber()])
            .exceptionally(e -> {
                log.debug("Failed to unmatch", e);
                return UnmatchResult.ERROR;
            });
    }

    @Override
    public void stop() {
        // close tenant logger and drain logs before closing the dist client
        if (closed.compareAndSet(false, true)) {
            log.info("Stopping dist client");
            log.debug("Closing request scheduler");
            reqScheduler.close();
            log.debug("Stopping rpc client");
            rpcClient.stop();
            log.info("Dist client stopped");
        }
    }
}
