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

import static com.baidu.bifromq.dist.entity.EntityUtil.toQInboxId;
import static com.baidu.bifromq.dist.entity.EntityUtil.toScopedTopicFilter;

import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basekv.client.scheduler.BatchMutationCall;
import com.baidu.bifromq.basekv.client.scheduler.MutationCallBatcherKey;
import com.baidu.bifromq.basekv.proto.KVRangeId;
import com.baidu.bifromq.basekv.store.proto.RWCoProcInput;
import com.baidu.bifromq.basekv.store.proto.RWCoProcOutput;
import com.baidu.bifromq.basescheduler.ICallTask;
import com.baidu.bifromq.dist.rpc.proto.BatchUnmatchReply;
import com.baidu.bifromq.dist.rpc.proto.BatchUnmatchRequest;
import com.baidu.bifromq.dist.rpc.proto.DistServiceRWCoProcInput;
import com.baidu.bifromq.dist.rpc.proto.UnmatchReply;
import com.baidu.bifromq.dist.rpc.proto.UnmatchRequest;
import java.time.Duration;
import java.util.Iterator;
import java.util.Queue;

public class BatchUnmatchCall extends BatchMutationCall<UnmatchRequest, UnmatchReply> {
    BatchUnmatchCall(KVRangeId rangeId,
                     IBaseKVStoreClient distWorkerClient,
                     Duration pipelineExpiryTime) {
        super(rangeId, distWorkerClient, pipelineExpiryTime);
    }

    @Override
    protected RWCoProcInput makeBatch(Iterator<UnmatchRequest> reqIterator) {
        BatchUnmatchRequest.Builder reqBuilder = BatchUnmatchRequest.newBuilder();
        while (reqIterator.hasNext()) {
            UnmatchRequest unmatchReq = reqIterator.next();
            String qInboxId =
                toQInboxId(unmatchReq.getBrokerId(), unmatchReq.getReceiverId(), unmatchReq.getDelivererKey());
            String scopedTopicFilter =
                toScopedTopicFilter(unmatchReq.getTenantId(), qInboxId, unmatchReq.getTopicFilter());
            reqBuilder.putScopedTopicFilter(scopedTopicFilter, unmatchReq.getIncarnation());
        }
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
        callTask.resultPromise().completeExceptionally(e);
    }

    @Override
    protected void handleOutput(Queue<ICallTask<UnmatchRequest, UnmatchReply, MutationCallBatcherKey>> batchedTasks,
                                RWCoProcOutput output) {
        ICallTask<UnmatchRequest, UnmatchReply, MutationCallBatcherKey> callTask;
        while ((callTask = batchedTasks.poll()) != null) {
            BatchUnmatchReply reply = output.getDistService().getBatchUnmatch();
            UnmatchRequest request = callTask.call();
            String qInboxId = toQInboxId(request.getBrokerId(), request.getReceiverId(), request.getDelivererKey());
            String scopedTopicFilter = toScopedTopicFilter(request.getTenantId(), qInboxId, request.getTopicFilter());
            BatchUnmatchReply.Result result =
                reply.getResultsOrDefault(scopedTopicFilter, BatchUnmatchReply.Result.ERROR);
            UnmatchReply.Result unmatchResult = switch (result) {
                case OK:
                    yield UnmatchReply.Result.OK;
                case NOT_EXISTED:
                    yield UnmatchReply.Result.NOT_EXISTED;
                case ERROR:
                default:
                    yield UnmatchReply.Result.ERROR;
            };

            callTask.resultPromise().complete(UnmatchReply.newBuilder()
                .setReqId(callTask.call().getReqId())
                .setResult(unmatchResult)
                .build());
        }
    }
}
