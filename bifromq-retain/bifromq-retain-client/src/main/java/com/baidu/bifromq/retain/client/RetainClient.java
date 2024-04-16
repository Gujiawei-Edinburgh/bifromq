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

package com.baidu.bifromq.retain.client;

import static com.baidu.bifromq.metrics.TenantMetric.MqttIngressRetainBytes;
import static com.baidu.bifromq.metrics.TenantMetric.MqttRetainMatchCount;
import static com.baidu.bifromq.metrics.TenantMetric.MqttRetainedBytes;

import com.baidu.bifromq.basehlc.HLC;
import com.baidu.bifromq.baserpc.IRPCClient;
import com.baidu.bifromq.metrics.ITenantMeter;
import com.baidu.bifromq.retain.RPCBluePrint;
import com.baidu.bifromq.retain.rpc.proto.ExpireAllReply;
import com.baidu.bifromq.retain.rpc.proto.ExpireAllRequest;
import com.baidu.bifromq.retain.rpc.proto.MatchReply;
import com.baidu.bifromq.retain.rpc.proto.MatchRequest;
import com.baidu.bifromq.retain.rpc.proto.RetainReply;
import com.baidu.bifromq.retain.rpc.proto.RetainRequest;
import com.baidu.bifromq.retain.rpc.proto.RetainServiceGrpc;
import com.baidu.bifromq.type.ClientInfo;
import com.baidu.bifromq.type.Message;
import com.baidu.bifromq.type.QoS;
import com.google.protobuf.ByteString;
import io.reactivex.rxjava3.core.Observable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class RetainClient implements IRetainClient {
    private final IRPCClient rpcClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    RetainClient(RetainClientBuilder builder) {
        this.rpcClient = IRPCClient.newBuilder()
            .bluePrint(RPCBluePrint.INSTANCE)
            .executor(builder.executor)
            .eventLoopGroup(builder.eventLoopGroup)
            .sslContext(builder.sslContext)
            .crdtService(builder.crdtService)
            .build();
    }

    @Override
    public void stop() {
        if (closed.compareAndSet(false, true)) {
            log.info("Stopping retain client");
            log.debug("Stopping rpc client");
            rpcClient.stop();
            log.debug("Retain client stopped");
        }
    }

    @Override
    public Observable<IRPCClient.ConnState> connState() {
        return rpcClient.connState();
    }

    @Override
    public CompletableFuture<MatchReply> match(MatchRequest request) {
        ITenantMeter tenantMeter = ITenantMeter.get(request.getTenantId());
        tenantMeter.recordCount(MqttRetainMatchCount);
        return rpcClient.invoke(request.getTenantId(), null, request, RetainServiceGrpc.getMatchMethod())
            .exceptionally(e -> {
                log.debug("Failed to match", e);
                return MatchReply.newBuilder()
                    .setReqId(request.getReqId())
                    .setResult(MatchReply.Result.ERROR)
                    .build();
            });
    }

    @Override
    public CompletableFuture<RetainReply> retain(long reqId, String topic, QoS qos, ByteString payload,
                                                 int expirySeconds, ClientInfo publisher) {
        ITenantMeter tenantMeter = ITenantMeter.get(publisher.getTenantId());
        if (!payload.isEmpty()) {
            tenantMeter.recordSummary(MqttIngressRetainBytes, payload.size());
        }
        // TODO: add throttler
        return rpcClient.invoke(publisher.getTenantId(), null, RetainRequest.newBuilder()
                .setReqId(reqId)
                .setTopic(topic)
                .setMessage(Message.newBuilder()
                    .setMessageId(0) // no seq needed
                    .setPubQoS(qos)
                    .setPayload(payload)
                    .setTimestamp(HLC.INST.getPhysical())
                    .setExpiryInterval(expirySeconds)
                    .build())
                .setPublisher(publisher)
                .build(), RetainServiceGrpc.getRetainMethod())
            .exceptionally(e -> {
                log.debug("Failed to retain", e);
                return RetainReply.newBuilder()
                    .setReqId(reqId)
                    .setResult(RetainReply.Result.ERROR)
                    .build();
            })
            .thenApply(retainReply -> {
                if (retainReply.getResult() == RetainReply.Result.RETAINED) {
                    tenantMeter.recordSummary(MqttRetainedBytes, payload.size());
                }
                return retainReply;
            });
    }

    @Override
    public CompletableFuture<ExpireAllReply> expireAll(ExpireAllRequest request) {
        return rpcClient.invoke(request.getTenantId(), null, request, RetainServiceGrpc.getExpireAllMethod())
            .exceptionally(e -> {
                log.debug("Failed to retain", e);
                return ExpireAllReply.newBuilder()
                    .setReqId(request.getReqId())
                    .setResult(ExpireAllReply.Result.ERROR)
                    .build();
            });
    }
}
