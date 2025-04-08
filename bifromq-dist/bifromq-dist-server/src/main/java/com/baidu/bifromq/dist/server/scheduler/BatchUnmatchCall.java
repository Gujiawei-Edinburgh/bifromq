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

package com.baidu.bifromq.dist.server.scheduler;

import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basekv.client.exception.BadVersionException;
import com.baidu.bifromq.basekv.client.exception.TryLaterException;
import com.baidu.bifromq.basekv.client.scheduler.BatchMutationCall;
import com.baidu.bifromq.basekv.client.scheduler.MutationCallBatcherKey;
import com.baidu.bifromq.basekv.store.proto.RWCoProcInput;
import com.baidu.bifromq.basekv.store.proto.RWCoProcOutput;
import com.baidu.bifromq.baserpc.client.exception.ServerNotFoundException;
import com.baidu.bifromq.basescheduler.ICallTask;
import com.baidu.bifromq.dist.rpc.proto.BatchUnmatchReply;
import com.baidu.bifromq.dist.rpc.proto.BatchUnmatchRequest;
import com.baidu.bifromq.dist.rpc.proto.DistServiceRWCoProcInput;
import com.baidu.bifromq.dist.rpc.proto.MatchRoute;
import com.baidu.bifromq.dist.rpc.proto.UnmatchReply;
import com.baidu.bifromq.dist.rpc.proto.UnmatchRequest;
import com.google.common.collect.Iterables;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class BatchUnmatchCall extends BatchMutationCall<UnmatchRequest, UnmatchReply> {
    BatchUnmatchCall(IBaseKVStoreClient distWorkerClient,
                     Duration pipelineExpiryTime,
                     MutationCallBatcherKey batcherKey) {
        super(distWorkerClient, pipelineExpiryTime, batcherKey);
    }

    @Override
    protected RWCoProcInput makeBatch(
        Iterable<ICallTask<UnmatchRequest, UnmatchReply, MutationCallBatcherKey>> callTasks) {
        BatchUnmatchRequest.Builder reqBuilder = BatchUnmatchRequest.newBuilder();
        Map<String, BatchUnmatchRequest.TenantBatch.Builder> builders = new HashMap<>();
        Iterator<UnmatchRequest> reqIterator = Iterables.transform(callTasks, ICallTask::call).iterator();
        while (reqIterator.hasNext()) {
            UnmatchRequest unmatchReq = reqIterator.next();
            builders.computeIfAbsent(unmatchReq.getTenantId(), k -> BatchUnmatchRequest.TenantBatch.newBuilder())
                .addRoute(MatchRoute.newBuilder()
                    .setMatcher(unmatchReq.getMatcher())
                    .setReceiverId(unmatchReq.getReceiverId())
                    .setDelivererKey(unmatchReq.getDelivererKey())
                    .setBrokerId(unmatchReq.getBrokerId())
                    .setIncarnation(unmatchReq.getIncarnation())
                    .build());
        }
        builders.forEach((t, builder) -> reqBuilder.putRequests(t, builder.build()));
        long reqId = System.nanoTime();
        return RWCoProcInput.newBuilder()
            .setDistService(DistServiceRWCoProcInput.newBuilder()
                .setBatchUnmatch(reqBuilder
                    .setReqId(reqId)
                    .build())
                .build())
            .build();
    }

    @Override
    protected void handleException(ICallTask<UnmatchRequest, UnmatchReply, MutationCallBatcherKey> callTask,
                                   Throwable e) {
        if (e instanceof ServerNotFoundException || e.getCause() instanceof ServerNotFoundException) {
            callTask.resultPromise().complete(UnmatchReply.newBuilder()
                .setReqId(callTask.call().getReqId())
                .setResult(UnmatchReply.Result.TRY_LATER)
                .build());
            return;
        }
        if (e instanceof BadVersionException || e.getCause() instanceof BadVersionException) {
            callTask.resultPromise().complete(UnmatchReply.newBuilder()
                .setReqId(callTask.call().getReqId())
                .setResult(UnmatchReply.Result.TRY_LATER)
                .build());
            return;
        }
        if (e instanceof TryLaterException || e.getCause() instanceof TryLaterException) {
            callTask.resultPromise().complete(UnmatchReply.newBuilder()
                .setReqId(callTask.call().getReqId())
                .setResult(UnmatchReply.Result.TRY_LATER)
                .build());
            return;
        }
        callTask.resultPromise().completeExceptionally(e);
    }

    @Override
    protected void handleOutput(Queue<ICallTask<UnmatchRequest, UnmatchReply, MutationCallBatcherKey>> batchedTasks,
                                RWCoProcOutput output) {
        ICallTask<UnmatchRequest, UnmatchReply, MutationCallBatcherKey> callTask;
        BatchUnmatchReply reply = output.getDistService().getBatchUnmatch();
        Map<String, List<ICallTask<UnmatchRequest, UnmatchReply, MutationCallBatcherKey>>> tasksByTenant =
            new HashMap<>();
        while ((callTask = batchedTasks.poll()) != null) {
            tasksByTenant.computeIfAbsent(callTask.call().getTenantId(), k -> new ArrayList<>()).add(callTask);
        }
        for (String tenantId : tasksByTenant.keySet()) {
            List<ICallTask<UnmatchRequest, UnmatchReply, MutationCallBatcherKey>> tasks = tasksByTenant.get(tenantId);
            List<BatchUnmatchReply.TenantBatch.Code> codes = reply.getResultsMap().get(tenantId).getCodeList();
            assert tasks.size() == codes.size();
            for (int i = 0; i < tasks.size(); i++) {
                UnmatchReply.Result unmatchResult = switch (codes.get(i)) {
                    case OK:
                        yield UnmatchReply.Result.OK;
                    case NOT_EXISTED:
                        yield UnmatchReply.Result.NOT_EXISTED;
                    default:
                        log.error("Unexpected unmatch result: {}", codes.get(i));
                        yield UnmatchReply.Result.ERROR;
                };
                tasks.get(i)
                    .resultPromise()
                    .complete(UnmatchReply.newBuilder()
                        .setReqId(tasks.get(i).call().getReqId())
                        .setResult(unmatchResult)
                        .build());
            }
        }
    }
}
