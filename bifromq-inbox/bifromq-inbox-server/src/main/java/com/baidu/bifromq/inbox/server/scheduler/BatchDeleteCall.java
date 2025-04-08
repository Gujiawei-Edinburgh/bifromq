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

package com.baidu.bifromq.inbox.server.scheduler;

import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basekv.client.exception.BadVersionException;
import com.baidu.bifromq.basekv.client.exception.TryLaterException;
import com.baidu.bifromq.basekv.client.scheduler.BatchMutationCall;
import com.baidu.bifromq.basekv.client.scheduler.MutationCallBatcherKey;
import com.baidu.bifromq.basekv.store.proto.RWCoProcInput;
import com.baidu.bifromq.basekv.store.proto.RWCoProcOutput;
import com.baidu.bifromq.baserpc.client.exception.ServerNotFoundException;
import com.baidu.bifromq.basescheduler.ICallTask;
import com.baidu.bifromq.inbox.record.InboxInstance;
import com.baidu.bifromq.inbox.record.TenantInboxInstance;
import com.baidu.bifromq.inbox.rpc.proto.DeleteReply;
import com.baidu.bifromq.inbox.rpc.proto.DeleteRequest;
import com.baidu.bifromq.inbox.storage.proto.BatchDeleteReply;
import com.baidu.bifromq.inbox.storage.proto.BatchDeleteRequest;
import com.baidu.bifromq.inbox.storage.proto.InboxServiceRWCoProcInput;
import java.time.Duration;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class BatchDeleteCall extends BatchMutationCall<DeleteRequest, DeleteReply> {

    protected BatchDeleteCall(IBaseKVStoreClient distWorkerClient,
                              Duration pipelineExpiryTime,
                              MutationCallBatcherKey batcherKey) {
        super(distWorkerClient, pipelineExpiryTime, batcherKey);
    }

    @Override
    protected MutationCallTaskBatch<DeleteRequest, DeleteReply> newBatch(String storeId,
                                                                         long ver) {
        return new BatchDeleteCallTask(storeId, ver);
    }

    @Override
    protected RWCoProcInput makeBatch(
        Iterable<ICallTask<DeleteRequest, DeleteReply, MutationCallBatcherKey>> callTasks) {
        BatchDeleteRequest.Builder reqBuilder = BatchDeleteRequest.newBuilder();
        callTasks.forEach(call -> {
            DeleteRequest request = call.call();
            BatchDeleteRequest.Params.Builder paramsBuilder = BatchDeleteRequest.Params.newBuilder()
                .setTenantId(request.getTenantId())
                .setInboxId(request.getInboxId())
                .setIncarnation(request.getIncarnation()) // new incarnation
                .setVersion(request.getVersion());
            reqBuilder.addParams(paramsBuilder.build());
        });
        long reqId = System.nanoTime();
        return RWCoProcInput.newBuilder()
            .setInboxService(InboxServiceRWCoProcInput.newBuilder()
                .setReqId(reqId)
                .setBatchDelete(reqBuilder.build())
                .build())
            .build();
    }

    @Override
    protected void handleOutput(Queue<ICallTask<DeleteRequest, DeleteReply, MutationCallBatcherKey>> batchedTasks,
                                RWCoProcOutput output) {
        ICallTask<DeleteRequest, DeleteReply, MutationCallBatcherKey> callTask;
        assert batchedTasks.size() == output.getInboxService().getBatchDelete().getResultCount();

        int i = 0;
        while ((callTask = batchedTasks.poll()) != null) {
            BatchDeleteReply.Result result = output.getInboxService().getBatchDelete().getResult(i++);
            DeleteReply.Builder replyBuilder = DeleteReply.newBuilder().setReqId(callTask.call().getReqId());
            switch (result.getCode()) {
                case OK -> callTask.resultPromise().complete(replyBuilder
                    .setCode(DeleteReply.Code.OK)
                    .putAllTopicFilters(result.getTopicFiltersMap())
                    .build());
                case NO_INBOX -> callTask.resultPromise().complete(replyBuilder
                    .setCode(DeleteReply.Code.NO_INBOX)
                    .build());
                case CONFLICT -> callTask.resultPromise().complete(replyBuilder
                    .setCode(DeleteReply.Code.CONFLICT)
                    .build());
                default -> {
                    log.error("Unexpected delete result: {}", result.getCode());
                    callTask.resultPromise().complete(replyBuilder
                        .setCode(DeleteReply.Code.ERROR)
                        .build());
                }
            }
        }
    }

    @Override
    protected void handleException(ICallTask<DeleteRequest, DeleteReply, MutationCallBatcherKey> callTask,
                                   Throwable e) {
        if (e instanceof ServerNotFoundException || e.getCause() instanceof ServerNotFoundException) {
            callTask.resultPromise().complete(DeleteReply.newBuilder()
                .setReqId(callTask.call().getReqId())
                .setCode(DeleteReply.Code.TRY_LATER)
                .build());
            return;
        }
        if (e instanceof BadVersionException || e.getCause() instanceof BadVersionException) {
            callTask.resultPromise().complete(DeleteReply.newBuilder()
                .setReqId(callTask.call().getReqId())
                .setCode(DeleteReply.Code.TRY_LATER)
                .build());
            return;
        }
        if (e instanceof TryLaterException || e.getCause() instanceof TryLaterException) {
            callTask.resultPromise().complete(DeleteReply.newBuilder()
                .setReqId(callTask.call().getReqId())
                .setCode(DeleteReply.Code.TRY_LATER)
                .build());
            return;
        }
        callTask.resultPromise().completeExceptionally(e);
    }

    private static class BatchDeleteCallTask extends MutationCallTaskBatch<DeleteRequest, DeleteReply> {
        private final Set<TenantInboxInstance> inboxes = new HashSet<>();

        private BatchDeleteCallTask(String storeId, long ver) {
            super(storeId, ver);
        }

        @Override
        protected void add(
            ICallTask<DeleteRequest, DeleteReply, MutationCallBatcherKey> callTask) {
            super.add(callTask);
            inboxes.add(new TenantInboxInstance(
                callTask.call().getTenantId(),
                new InboxInstance(callTask.call().getInboxId(), callTask.call().getIncarnation()))
            );
        }

        @Override
        protected boolean isBatchable(
            ICallTask<DeleteRequest, DeleteReply, MutationCallBatcherKey> callTask) {
            return !inboxes.contains(new TenantInboxInstance(
                callTask.call().getTenantId(),
                new InboxInstance(callTask.call().getInboxId(), callTask.call().getIncarnation()))
            );
        }
    }
}
