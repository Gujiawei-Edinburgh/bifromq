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

package com.baidu.bifromq.apiserver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import com.baidu.bifromq.basecluster.IAgentHost;
import com.baidu.bifromq.basekv.metaservice.IBaseKVMetaService;
import com.baidu.bifromq.baserpc.trafficgovernor.IRPCServiceTrafficGovernor;
import com.baidu.bifromq.dist.client.IDistClient;
import com.baidu.bifromq.dist.client.PubResult;
import com.baidu.bifromq.inbox.client.IInboxClient;
import com.baidu.bifromq.mqtt.inbox.IMqttBrokerClient;
import com.baidu.bifromq.plugin.settingprovider.ISettingProvider;
import com.baidu.bifromq.plugin.settingprovider.Setting;
import com.baidu.bifromq.retain.client.IRetainClient;
import com.baidu.bifromq.retain.rpc.proto.RetainReply;
import com.baidu.bifromq.sessiondict.client.ISessionDictClient;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.reactivex.rxjava3.core.Observable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import lombok.SneakyThrows;
import org.mockito.Mock;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class APIServerTest extends MockableTest {
    private final String host = "127.0.0.1";
    private APIServer apiServer;
    @Mock
    private IAgentHost agentHost;
    @Mock
    private IBaseKVMetaService metaService;
    @Mock
    private IRPCServiceTrafficGovernor trafficGovernor;
    @Mock
    private IMqttBrokerClient brokerClient;
    @Mock
    private IDistClient distClient;
    @Mock
    private IInboxClient inboxClient;
    @Mock
    private ISessionDictClient sessionDictClient;
    @Mock
    private IRetainClient retainClient;
    private ISettingProvider settingProvider = Setting::current;

    @BeforeMethod(alwaysRun = true)
    public void setup() {
        super.setup();
        when(metaService.clusterIds()).thenReturn(Observable.empty());
        when(trafficGovernor.serverList()).thenReturn(Observable.empty());
        when(trafficGovernor.trafficRules()).thenReturn(Observable.empty());
        when(brokerClient.trafficGovernor()).thenReturn(trafficGovernor);
        when(distClient.trafficGovernor()).thenReturn(trafficGovernor);
        when(inboxClient.trafficGovernor()).thenReturn(trafficGovernor);
        when(sessionDictClient.trafficGovernor()).thenReturn(trafficGovernor);
        when(retainClient.trafficGovernor()).thenReturn(trafficGovernor);
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(1);
        apiServer = APIServer.builder()
            .host(host)
            .port(0)
            .tlsPort(0)
            .maxContentLength(1024 * 1024)
            .bossGroup(bossGroup)
            .workerGroup(workerGroup)
            .agentHost(agentHost)
            .metaService(metaService)
            .brokerClient(brokerClient)
            .distClient(distClient)
            .inboxClient(inboxClient)
            .sessionDictClient(sessionDictClient)
            .retainClient(retainClient)
            .settingProvider(settingProvider)
            .build();
        apiServer.start();
    }

    @AfterMethod(alwaysRun = true)
    public void teardown() {
        super.teardown();
        apiServer.shutdown();
    }

    @Test(groups = "integration")
    @SneakyThrows
    public void pub() {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest pubRequest = HttpRequest.newBuilder()
            .uri(URI.create(String.format("http://%s:%d/pub", host, apiServer.listeningPort())))
            .header(Headers.HEADER_TENANT_ID.header, "BifroMQ-Dev")
            .header(Headers.HEADER_TOPIC.header, "/greeting")
            .header(Headers.HEADER_CLIENT_TYPE.header, "BifroMQ Fan")
            .header(Headers.HEADER_RETAIN.header, "true")
            .header(Headers.HEADER_QOS.header, "1")
            .POST(HttpRequest.BodyPublishers.ofString("Hello BifroMQ"))
            .build();
        when(distClient.pub(anyLong(), anyString(), any(), any())).thenReturn(
            CompletableFuture.completedFuture(PubResult.OK));
        when(retainClient.retain(anyLong(), anyString(), any(), any(), anyInt(), any())).thenReturn(
            CompletableFuture.completedFuture(RetainReply.newBuilder().setResult(RetainReply.Result.RETAINED).build()));
        HttpResponse<?> resp = httpClient.send(pubRequest, HttpResponse.BodyHandlers.discarding());
        assertEquals(resp.statusCode(), 200);
    }
}
