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

package com.baidu.bifromq.dist.server.handler;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import com.baidu.bifromq.basescheduler.exception.BackPressureException;
import com.baidu.bifromq.dist.rpc.proto.MatchReply;
import com.baidu.bifromq.dist.rpc.proto.MatchRequest;
import com.baidu.bifromq.dist.server.scheduler.IMatchCallScheduler;
import com.baidu.bifromq.plugin.eventcollector.EventType;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.baidu.bifromq.plugin.eventcollector.distservice.MatchError;
import com.baidu.bifromq.plugin.eventcollector.distservice.Matched;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class MatchReqHandlerTest {
    private MatchReqHandler handler;
    private IEventCollector eventCollector;
    private IMatchCallScheduler matchCallScheduler;

    @BeforeMethod
    public void setUp() {
        eventCollector = mock(IEventCollector.class);
        matchCallScheduler = mock(IMatchCallScheduler.class);
        handler = new MatchReqHandler(eventCollector, matchCallScheduler);
    }

    @Test
    public void testHandleSuccess() throws ExecutionException, InterruptedException {
        MatchRequest request = MatchRequest.newBuilder()
            .setReqId(123)
            .build();
        MatchReply reply = MatchReply.newBuilder()
            .setReqId(123)
            .setResult(MatchReply.Result.OK)
            .build();
        CompletableFuture<MatchReply> future = CompletableFuture.completedFuture(reply);
        when(matchCallScheduler.schedule(request)).thenReturn(future);

        CompletableFuture<MatchReply> result = handler.handle(request);
        assertNotNull(result);
        assertEquals(MatchReply.Result.OK, result.get().getResult());
        verify(eventCollector, times(1)).report(any(Matched.class));
    }

    @Test
    public void testHandleExceedLimit() throws ExecutionException, InterruptedException {
        MatchRequest request = MatchRequest.newBuilder()
            .setReqId(123)
            .build();
        MatchReply reply = MatchReply.newBuilder()
            .setReqId(123)
            .setResult(MatchReply.Result.EXCEED_LIMIT)
            .build();
        CompletableFuture<MatchReply> future = CompletableFuture.completedFuture(reply);
        when(matchCallScheduler.schedule(request)).thenReturn(future);

        CompletableFuture<MatchReply> result = handler.handle(request);
        assertNotNull(result);
        assertEquals(MatchReply.Result.EXCEED_LIMIT, result.get().getResult());
        verify(eventCollector, times(1)).report(argThat(e ->
            e.type() == EventType.MATCH_ERROR && ((MatchError) e).reason().contains("EXCEED_LIMIT")));
    }

    @Test
    public void testHandleError() throws ExecutionException, InterruptedException {
        MatchRequest request = MatchRequest.newBuilder()
            .setReqId(123)
            .build();
        MatchReply reply = MatchReply.newBuilder()
            .setReqId(123)
            .setResult(MatchReply.Result.ERROR)
            .build();
        CompletableFuture<MatchReply> future = CompletableFuture.completedFuture(reply);
        when(matchCallScheduler.schedule(request)).thenReturn(future);

        CompletableFuture<MatchReply> result = handler.handle(request);
        assertNotNull(result);
        assertEquals(MatchReply.Result.ERROR, result.get().getResult());
        verify(eventCollector, times(1)).report(argThat(e ->
            e.type() == EventType.MATCH_ERROR && ((MatchError) e).reason().contains("Internal Error")));
    }

    @Test
    public void testHandleBackPressureException() {
        MatchRequest request = MatchRequest.newBuilder().setReqId(123).build();
        CompletableFuture<MatchReply> failedFuture = CompletableFuture.supplyAsync(() -> {
            throw new BackPressureException("Back pressure");
        });
        when(matchCallScheduler.schedule(request)).thenReturn(failedFuture);

        MatchReply result = handler.handle(request).join();
        assertEquals(MatchReply.Result.BACK_PRESSURE_REJECTED, result.getResult());
        verify(eventCollector, times(1)).report(argThat(e ->
            e.type() == EventType.MATCH_ERROR && ((MatchError) e).reason().contains("Back pressure")));
    }

    @Test
    public void testCloseMethod() {
        // There is no output to assert here, only behavior to verify.
        handler.close();
        // Verify that close was called on the matchCallScheduler
        verify(matchCallScheduler, times(1)).close();
    }
}
