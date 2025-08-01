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

import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.apache.bifromq.apiserver.http.handler.utils.JSONUtils.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.bifromq.apiserver.Headers;
import org.apache.bifromq.apiserver.http.handler.utils.HeaderUtils;
import org.apache.bifromq.basekv.metaservice.IBaseKVLandscapeObserver;
import org.apache.bifromq.basekv.metaservice.IBaseKVMetaService;
import org.apache.bifromq.basekv.proto.KVRangeStoreDescriptor;
import org.apache.bifromq.baserpc.trafficgovernor.IRPCServiceTrafficGovernor;
import org.apache.bifromq.baserpc.trafficgovernor.IRPCServiceTrafficService;
import org.apache.bifromq.baserpc.trafficgovernor.ServerEndpoint;

@Path("/store/landscape")
class GetStoreLandscapeHandler extends AbstractLandscapeHandler {
    private static final String STORE_SERVICE_NAME_SUFFIX = "@basekv.BaseKVStoreService";
    protected final Map<String, IRPCServiceTrafficGovernor> governorMap = new ConcurrentHashMap<>();
    private final IRPCServiceTrafficService trafficService;
    private final CompositeDisposable disposable = new CompositeDisposable();

    GetStoreLandscapeHandler(IBaseKVMetaService metaService, IRPCServiceTrafficService trafficService) {
        super(metaService);
        this.trafficService = trafficService;
    }

    public static JsonNode toJSON(Map<ServerEndpoint, KVRangeStoreDescriptor> landscape) {
        ArrayNode rootObject = MAPPER.createArrayNode();
        for (ServerEndpoint server : landscape.keySet()) {
            KVRangeStoreDescriptor storeDescriptor = landscape.get(server);
            ObjectNode storeNodeObject = MAPPER.createObjectNode();
            storeNodeObject.put("hostId", Base64.getEncoder().encodeToString(server.hostId().toByteArray()));
            storeNodeObject.put("id", storeDescriptor.getId());
            storeNodeObject.put("address", server.address());
            storeNodeObject.put("port", server.port());

            ObjectNode attrsObject = MAPPER.createObjectNode();
            for (String attrName : storeDescriptor.getAttributesMap().keySet()) {
                attrsObject.put(attrName, storeDescriptor.getAttributesMap().get(attrName));
            }
            storeNodeObject.set("attributes", attrsObject);
            rootObject.add(storeNodeObject);
        }
        return rootObject;
    }

    @Override
    public void start() {
        super.start();
        disposable.add(trafficService.services().subscribe(serviceUniqueNames -> {
            governorMap.keySet().removeIf(serviceUniqueName -> !serviceUniqueNames.contains(serviceUniqueName));
            for (String serviceUniqueName : serviceUniqueNames) {
                if (serviceUniqueName.endsWith(STORE_SERVICE_NAME_SUFFIX)) {
                    governorMap.computeIfAbsent(serviceUniqueName, trafficService::getTrafficGovernor);
                }
            }
        }));
    }

    @Override
    public void close() {
        super.close();
        disposable.dispose();
    }

    @GET
    @Operation(summary = "Get the store landscape information")
    @Parameters({
        @Parameter(name = "req_id", in = ParameterIn.HEADER,
            description = "optional caller provided request id",
            schema = @Schema(implementation = Long.class)),
        @Parameter(name = "store_name", in = ParameterIn.HEADER, required = true,
            description = "the store name",
            schema = @Schema(implementation = String.class))
    })
    @RequestBody(required = false)
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200",
            description = "Success",
            content = @Content(mediaType = "application/json")),
        @ApiResponse(responseCode = "404",
            description = "Store not found",
            content = @Content(schema = @Schema(implementation = String.class))),
    })
    @Override
    public CompletableFuture<FullHttpResponse> handle(@Parameter(hidden = true) long reqId,
                                                      @Parameter(hidden = true) FullHttpRequest req) {
        String storeName = HeaderUtils.getHeader(Headers.HEADER_STORE_NAME, req, true);
        IBaseKVLandscapeObserver landscapeObserver = landscapeObservers.get(storeName);
        IRPCServiceTrafficGovernor trafficGovernor = governorMap.get(storeName + STORE_SERVICE_NAME_SUFFIX);
        if (landscapeObserver == null || trafficGovernor == null) {
            return CompletableFuture.completedFuture(new DefaultFullHttpResponse(req.protocolVersion(), NOT_FOUND,
                Unpooled.copiedBuffer(("Service not found: " + storeName).getBytes())));
        }

        return Observable.combineLatest(landscapeObserver.landscape(), trafficGovernor.serverEndpoints(),
                (stores, serverEndpoints) -> {
                    Map<ServerEndpoint, KVRangeStoreDescriptor> storeToServer = new HashMap<>();
                    for (ServerEndpoint serverEndpoint : serverEndpoints) {
                        String storeId = serverEndpoint.attrs().get("store_id");
                        if (storeId != null && stores.containsKey(storeId)) {
                            storeToServer.put(serverEndpoint, stores.get(storeId));
                        }
                    }
                    return storeToServer;
                })
            .firstElement()
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(serverToStore -> {
                DefaultFullHttpResponse
                    resp = new DefaultFullHttpResponse(req.protocolVersion(), OK,
                    Unpooled.wrappedBuffer(toJSON(serverToStore).toString().getBytes()));
                resp.headers().set("Content-Type", "application/json");
                return resp;
            });
    }
}
