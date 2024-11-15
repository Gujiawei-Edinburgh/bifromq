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

package com.baidu.bifromq.apiserver.http.handler;

import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import com.baidu.bifromq.apiserver.Headers;
import com.baidu.bifromq.apiserver.http.IHTTPRequestHandler;
import com.baidu.bifromq.baserpc.trafficgovernor.IRPCServiceLandscape;
import com.baidu.bifromq.baserpc.trafficgovernor.IRPCServiceTrafficService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.reactivex.rxjava3.core.Single;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Path("/rules/traffic")
public class GetTrafficRulesHandler implements IHTTPRequestHandler {
    private final Map<String, IRPCServiceLandscape> landscapeMap = new ConcurrentHashMap<>();

    public GetTrafficRulesHandler(IRPCServiceTrafficService trafficService) {
        trafficService.services().subscribe(serviceUniqueNames -> {
            landscapeMap.keySet().removeIf(serviceUniqueName -> !serviceUniqueNames.contains(serviceUniqueName));
            for (String serviceUniqueName : serviceUniqueNames) {
                landscapeMap.computeIfAbsent(serviceUniqueName, trafficService::getServiceLandscape);
            }
        });
    }

    @GET
    @Operation(summary = "Get the traffic rules")
    @Parameters({
        @Parameter(name = "req_id", in = ParameterIn.HEADER,
            description = "optional caller provided request id", schema = @Schema(implementation = Long.class)),
        @Parameter(name = "service_name", in = ParameterIn.HEADER, required = true,
            description = "the service name", schema = @Schema(implementation = String.class))
    })
    @RequestBody(required = false)
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Success"),
    })
    @Override
    public CompletableFuture<FullHttpResponse> handle(long reqId, FullHttpRequest req) {
        log.trace("Handling http get traffic rules request: {}", req);
        String serviceName = HeaderUtils.getHeader(Headers.HEADER_SERVICE_NAME, req, true);
        IRPCServiceLandscape landscape = landscapeMap.get(serviceName);
        if (landscape == null) {
            return CompletableFuture.completedFuture(new DefaultFullHttpResponse(req.protocolVersion(), NOT_FOUND,
                Unpooled.copiedBuffer(("Service not found: " + serviceName).getBytes())));
        }
        return landscape.trafficRules()
            .firstElement()
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(trafficDirective -> {
                DefaultFullHttpResponse
                    resp = new DefaultFullHttpResponse(req.protocolVersion(), OK,
                    Unpooled.wrappedBuffer(toJSON(trafficDirective).getBytes()));
                resp.headers().set("Content-Type", "application/json");
                return resp;
            });
    }

    private String toJSON(Map<String, Map<String, Integer>> trafficDirective) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode rootObject = mapper.createObjectNode();
        for (String tenantIdPrefix : trafficDirective.keySet()) {
            Map<String, Integer> groupWeights = trafficDirective.get(tenantIdPrefix);
            ObjectNode groupWeightsObject = mapper.createObjectNode();
            for (String group : groupWeights.keySet()) {
                groupWeightsObject.put(group, groupWeights.get(group));
            }
            rootObject.set(tenantIdPrefix, groupWeightsObject);
        }
        return rootObject.toString();
    }
}
