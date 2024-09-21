/*
 * Copyright (c) 2024. The BifroMQ Authors. All Rights Reserved.
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

import static com.baidu.bifromq.basekv.utils.BoundaryUtil.FULL_BOUNDARY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.anySet;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import com.baidu.bifromq.basekv.proto.Boundary;
import com.baidu.bifromq.basekv.proto.KVRangeId;
import com.baidu.bifromq.basekv.store.api.IKVCloseableReader;
import com.baidu.bifromq.basekv.store.api.IKVIterator;
import com.baidu.bifromq.basekv.store.api.IKVWriter;
import com.baidu.bifromq.basekv.store.proto.ROCoProcInput;
import com.baidu.bifromq.basekv.store.proto.ROCoProcOutput;
import com.baidu.bifromq.basekv.store.proto.RWCoProcInput;
import com.baidu.bifromq.basekv.store.proto.RWCoProcOutput;
import com.baidu.bifromq.dist.entity.EntityUtil;
import com.baidu.bifromq.dist.entity.Matching;
import com.baidu.bifromq.dist.rpc.proto.BatchDistReply;
import com.baidu.bifromq.dist.rpc.proto.BatchDistRequest;
import com.baidu.bifromq.dist.rpc.proto.BatchMatchReply;
import com.baidu.bifromq.dist.rpc.proto.BatchMatchRequest;
import com.baidu.bifromq.dist.rpc.proto.BatchUnmatchReply;
import com.baidu.bifromq.dist.rpc.proto.BatchUnmatchRequest;
import com.baidu.bifromq.dist.rpc.proto.DistPack;
import com.baidu.bifromq.dist.rpc.proto.DistServiceROCoProcInput;
import com.baidu.bifromq.dist.rpc.proto.DistServiceRWCoProcInput;
import com.baidu.bifromq.dist.rpc.proto.TenantOption;
import com.baidu.bifromq.dist.worker.cache.ISubscriptionCache;
import com.baidu.bifromq.type.TopicMessagePack;
import com.google.protobuf.ByteString;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class DistWorkerCoProcTest {

    private ISubscriptionCache routeCache;
    private ITenantsState tenantsState;
    private IDeliverExecutorGroup deliverExecutorGroup;
    private Supplier<IKVCloseableReader> readerProvider;
    private IKVCloseableReader reader;
    private IKVWriter writer;
    private IKVIterator iterator;
    private KVRangeId rangeId;
    private DistWorkerCoProc distWorkerCoProc;

    @BeforeMethod
    public void setUp() {
        routeCache = mock(ISubscriptionCache.class);
        tenantsState = mock(ITenantsState.class);
        deliverExecutorGroup = mock(IDeliverExecutorGroup.class);
        readerProvider = mock(Supplier.class);
        reader = mock(IKVCloseableReader.class);
        iterator = mock(IKVIterator.class);
        writer = mock(IKVWriter.class);
        rangeId = KVRangeId.newBuilder().setId(1).setEpoch(1).build();
        when(readerProvider.get()).thenReturn(reader);
        when(reader.boundary()).thenReturn(FULL_BOUNDARY);
        when(reader.iterator()).thenReturn(iterator);
        when(iterator.isValid()).thenReturn(false);
        distWorkerCoProc =
            new DistWorkerCoProc(rangeId, readerProvider, routeCache, tenantsState, deliverExecutorGroup);
    }

    @Test
    public void testMutateBatchMatch() {
        String scopedTopicFilter1 = EntityUtil.toScopedTopicFilter("tenant1",
            EntityUtil.toQInboxId(1, "inbox1", "deliverer1"), "topicFilter1");
        String scopedTopicFilter2 = EntityUtil.toScopedTopicFilter("tenant2",
            EntityUtil.toQInboxId(1, "inbox2", "deliverer2"), "topicFilter2");
        RWCoProcInput rwCoProcInput = RWCoProcInput.newBuilder()
            .setDistService(DistServiceRWCoProcInput.newBuilder()
                .setBatchMatch(BatchMatchRequest.newBuilder()
                    .setReqId(123)
                    .addScopedTopicFilter(scopedTopicFilter1)
                    .addScopedTopicFilter(scopedTopicFilter2)
                    .putOptions("tenant1", TenantOption.newBuilder().setMaxReceiversPerSharedSubGroup(10).build())
                    .putOptions("tenant2", TenantOption.newBuilder().setMaxReceiversPerSharedSubGroup(5).build())
                    .build())
                .build())
            .build();

        when(reader.exist(any(ByteString.class))).thenReturn(false);

        // Simulate mutation
        Supplier<RWCoProcOutput> resultSupplier = distWorkerCoProc.mutate(rwCoProcInput, reader, writer);
        RWCoProcOutput result = resultSupplier.get();

        // Verify that matches are added to the cache
        verify(routeCache, times(1)).addAllMatch(any());

        // Verify that tenant state is updated for both tenants
        verify(tenantsState, times(1)).incNormalRoutes(eq("tenant1"), eq(1));
        verify(tenantsState, times(1)).incNormalRoutes(eq("tenant2"), eq(1));

        // Check the result output
        BatchMatchReply reply = result.getDistService().getBatchMatch();
        assertEquals(123, reply.getReqId());
        assertEquals(BatchMatchReply.Result.OK, reply.getResultsOrThrow(scopedTopicFilter1));
        assertEquals(BatchMatchReply.Result.OK, reply.getResultsOrThrow(scopedTopicFilter2));
    }

    @Test
    public void testMutateBatchUnmatch() {
        String scopedTopicFilter = EntityUtil.toScopedTopicFilter("tenant1",
            EntityUtil.toQInboxId(1, "inbox1", "deliverer1"), "topicFilter1");
        RWCoProcInput rwCoProcInput = RWCoProcInput.newBuilder()
            .setDistService(DistServiceRWCoProcInput.newBuilder()
                .setBatchUnmatch(BatchUnmatchRequest.newBuilder()
                    .setReqId(456)
                    .addScopedTopicFilter(scopedTopicFilter)
                    .build())
                .build())
            .build();

        // Simulate match exists in the reader
        when(reader.get(any(ByteString.class))).thenReturn(Optional.of(ByteString.EMPTY));

        // Simulate mutation
        Supplier<RWCoProcOutput> resultSupplier = distWorkerCoProc.mutate(rwCoProcInput, reader, writer);
        RWCoProcOutput result = resultSupplier.get();

        // Verify that matches are removed from the cache
        verify(routeCache, times(1)).removeAllMatch(argThat(m -> m.containsKey("tenant1")
            && m.get("tenant1").containsKey("topicFilter1")));

        // Verify that tenant state is updated
        verify(tenantsState, times(1)).decNormalRoutes(eq("tenant1"), eq(1));

        // Check the result output
        BatchUnmatchReply reply = result.getDistService().getBatchUnmatch();
        assertEquals(456, reply.getReqId());
        assertEquals(BatchUnmatchReply.Result.OK, reply.getResultsOrThrow(scopedTopicFilter));
    }

    @Test
    public void testQueryBatchDist() {
        ROCoProcInput roCoProcInput = ROCoProcInput.newBuilder()
            .setDistService(DistServiceROCoProcInput.newBuilder()
                .setBatchDist(BatchDistRequest.newBuilder()
                    .setReqId(789)
                    .addDistPack(DistPack.newBuilder()
                        .setTenantId("tenant1")
                        .addMsgPack(TopicMessagePack.newBuilder().setTopic("topic1").build())
                        .build())
                    .build())
                .build())
            .build();

        // Simulate routes in cache
        CompletableFuture<Set<Matching>> futureRoutes =
            CompletableFuture.completedFuture(
                Set.of(createMatching("tenant1", "topic1", EntityUtil.toQInboxId(1, "inbox1", "deliverer1"))));
        when(routeCache.get(eq("tenant1"), eq("topic1"))).thenReturn(futureRoutes);

        // Simulate query
        CompletableFuture<ROCoProcOutput> resultFuture = distWorkerCoProc.query(roCoProcInput, reader);
        ROCoProcOutput result = resultFuture.join();

        // Verify the submission to executor group
        verify(deliverExecutorGroup, times(1)).submit(eq("tenant1"), anySet(), any(TopicMessagePack.class));

        // Check the result output
        BatchDistReply reply = result.getDistService().getBatchDist();
        assertEquals(789, reply.getReqId());
    }

    @Test
    public void testReset() {
        Boundary boundary = Boundary.newBuilder()
            .setStartKey(ByteString.copyFromUtf8("start"))
            .setEndKey(ByteString.copyFromUtf8("end"))
            .build();
        when(reader.boundary()).thenReturn(boundary);
        distWorkerCoProc.reset(boundary);

        // Verify that tenant state and route cache are reset
        verify(tenantsState, times(1)).reset();
        verify(routeCache, times(1)).reset(eq(boundary));
    }

    @Test
    public void testClose() {
        distWorkerCoProc.close();

        // Verify that tenant state, route cache, and deliver executor group are closed
        verify(tenantsState, times(1)).close();
        verify(routeCache, times(1)).close();
        verify(deliverExecutorGroup, times(1)).shutdown();
    }

    private Matching createMatching(String tenantId, String topicFilter, String qInboxId) {
        // Sample data for creating a Matching object

        // Construct a ByteString for normal match record key
        ByteString normalMatchRecordKey = EntityUtil.toNormalMatchRecordKey(tenantId, topicFilter, qInboxId);

        // Construct the match record value (for example, an empty value for a normal match)
        ByteString matchRecordValue = ByteString.EMPTY;

        // Use EntityUtil to parse the key and value into a Matching object
        return EntityUtil.parseMatchRecord(normalMatchRecordKey, matchRecordValue);
    }
}