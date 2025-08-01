/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.bifromq.apiserver.http.handler;

import static org.apache.bifromq.apiserver.Headers.HEADER_BALANCER_FACTORY_CLASS;
import static org.apache.bifromq.apiserver.Headers.HEADER_STORE_NAME;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import com.google.protobuf.Struct;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.reactivex.rxjava3.subjects.Subject;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.apache.bifromq.basekv.metaservice.IBaseKVStoreBalancerStatesProposer;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class SetLoadRulesHandlerTest extends AbstractHTTPRequestHandlerTest<SetLoadRulesHandler> {

    private final Subject<Set<String>> mockClusterIdSubject = BehaviorSubject.create();

    @BeforeMethod
    public void setup() {
        super.setup();
        when(metaService.clusterIds()).thenReturn(mockClusterIdSubject);
    }

    @Override
    protected Class<SetLoadRulesHandler> handlerClass() {
        return SetLoadRulesHandler.class;
    }

    @Test
    public void noClusterFound() {
        DefaultFullHttpRequest req = buildRequest(HttpMethod.PUT);
        req.headers().set(HEADER_STORE_NAME.header, "fakeUserId");
        req.headers().set(HEADER_BALANCER_FACTORY_CLASS.header, "fakeBalancerClass");
        SetLoadRulesHandler handler = new SetLoadRulesHandler(metaService);
        FullHttpResponse resp = handler.handle(123, req).join();
        assertEquals(resp.status(), HttpResponseStatus.NOT_FOUND);
    }

    @Test
    public void clusterChanged() {
        String clusterId = "dist.worker";
        String balancerClass = "balancerClass";
        Struct loadRules = Struct.getDefaultInstance();
        DefaultFullHttpRequest req = buildRequest(HttpMethod.PUT, Unpooled.copiedBuffer("{}".getBytes()));
        req.headers().set(HEADER_STORE_NAME.header, clusterId);
        req.headers().set(HEADER_BALANCER_FACTORY_CLASS.header, balancerClass);
        when(metaService.balancerStatesProposer(eq(clusterId))).thenReturn(statesProposer);
        when(statesProposer.proposeLoadRules(eq(balancerClass), eq(loadRules)))
            .thenReturn(CompletableFuture.completedFuture(IBaseKVStoreBalancerStatesProposer.ProposalResult.ACCEPTED));

        SetLoadRulesHandler handler = new SetLoadRulesHandler(metaService);
        handler.start();
        FullHttpResponse resp = handler.handle(123, req).join();
        assertEquals(resp.status(), HttpResponseStatus.NOT_FOUND);

        mockClusterIdSubject.onNext(Set.of(clusterId));
        resp = handler.handle(123, req).join();
        assertEquals(resp.status(), HttpResponseStatus.OK);

        mockClusterIdSubject.onNext(Collections.emptySet());
        resp = handler.handle(123, req).join();
        assertEquals(resp.status(), HttpResponseStatus.NOT_FOUND);
    }

    @Test
    public void requestTimeout() {
        String clusterId = "dist.worker";
        String balancerClass = "balancerClass";
        Struct loadRules = Struct.getDefaultInstance();
        mockClusterIdSubject.onNext(Set.of(clusterId));
        when(metaService.balancerStatesProposer(eq(clusterId))).thenReturn(statesProposer);
        when(statesProposer.proposeLoadRules(eq(balancerClass), eq(loadRules))).thenReturn(
            CompletableFuture.failedFuture(new CompletionException(new TimeoutException("timeout"))));

        DefaultFullHttpRequest req = buildRequest(HttpMethod.PUT, Unpooled.copiedBuffer("{}".getBytes()));
        req.headers().set(HEADER_STORE_NAME.header, clusterId);
        req.headers().set(HEADER_BALANCER_FACTORY_CLASS.header, balancerClass);
        SetLoadRulesHandler handler = new SetLoadRulesHandler(metaService);
        handler.start();
        FullHttpResponse resp = handler.handle(123, req).join();
        assertEquals(resp.status(), HttpResponseStatus.REQUEST_TIMEOUT);
    }

    @Test
    public void requestAccepted() {
        String clusterId = "dist.worker";
        String balancerClass = "balancerClass";
        Struct loadRules = Struct.getDefaultInstance();
        mockClusterIdSubject.onNext(Set.of(clusterId));
        when(metaService.balancerStatesProposer(eq(clusterId))).thenReturn(statesProposer);

        when(statesProposer.proposeLoadRules(eq(balancerClass), eq(loadRules))).thenReturn(
            CompletableFuture.completedFuture(IBaseKVStoreBalancerStatesProposer.ProposalResult.ACCEPTED));
        DefaultFullHttpRequest req = buildRequest(HttpMethod.PUT, Unpooled.copiedBuffer("{}".getBytes()));
        req.headers().set(HEADER_STORE_NAME.header, clusterId);
        req.headers().set(HEADER_BALANCER_FACTORY_CLASS.header, balancerClass);
        SetLoadRulesHandler handler = new SetLoadRulesHandler(metaService);
        handler.start();
        FullHttpResponse resp = handler.handle(123, req).join();
        assertEquals(resp.status(), HttpResponseStatus.OK);
    }

    @Test
    public void requestConflict() {
        String clusterId = "dist.worker";
        String balancerClass = "balancerClass";
        Struct loadRules = Struct.getDefaultInstance();
        mockClusterIdSubject.onNext(Set.of(clusterId));
        when(metaService.balancerStatesProposer(eq(clusterId))).thenReturn(statesProposer);

        when(statesProposer.proposeLoadRules(eq(balancerClass), eq(loadRules)))
            .thenReturn(
                CompletableFuture.completedFuture(IBaseKVStoreBalancerStatesProposer.ProposalResult.OVERRIDDEN));
        DefaultFullHttpRequest req = buildRequest(HttpMethod.PUT, Unpooled.copiedBuffer("{}".getBytes()));
        req.headers().set(HEADER_STORE_NAME.header, clusterId);
        req.headers().set(HEADER_BALANCER_FACTORY_CLASS.header, balancerClass);
        SetLoadRulesHandler handler = new SetLoadRulesHandler(metaService);
        handler.start();
        FullHttpResponse resp = handler.handle(123, req).join();
        assertEquals(resp.status(), HttpResponseStatus.CONFLICT);
    }
}
