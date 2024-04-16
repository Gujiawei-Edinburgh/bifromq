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

package com.baidu.bifromq.dist.worker;

import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_CLIENT_ADDRESS_KEY;
import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_CLIENT_ID_KEY;
import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_PROTOCOL_VER_3_1_1_VALUE;
import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_PROTOCOL_VER_KEY;
import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_TYPE_VALUE;
import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_USER_ID_KEY;
import static com.baidu.bifromq.type.QoS.AT_MOST_ONCE;
import static com.google.protobuf.ByteString.copyFromUtf8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import com.baidu.bifromq.dist.rpc.proto.TenantDistReply;
import com.baidu.bifromq.plugin.subbroker.DeliveryResult;
import com.baidu.bifromq.type.ClientInfo;
import com.baidu.bifromq.type.Message;
import com.baidu.bifromq.type.QoS;
import com.baidu.bifromq.type.TopicMessagePack;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

@Slf4j
public class BatchDistTest extends DistWorkerTest {

    @Test(groups = "integration")
    public void batchDistWithNoSub() {
        ByteString payload = copyFromUtf8("hello");

        TenantDistReply reply = tenantDist(tenantA,
            List.of(TopicMessagePack.newBuilder()
                    .setTopic("a")
                    .addMessage(toMsg(tenantA, AT_MOST_ONCE, payload))
                    .build(),
                TopicMessagePack.newBuilder()
                    .setTopic("a/")
                    .addMessage(toMsg(tenantA, AT_MOST_ONCE, payload))
                    .build(),
                TopicMessagePack.newBuilder()
                    .setTopic("a/b")
                    .addMessage(toMsg(tenantA, AT_MOST_ONCE, payload))
                    .build()), "orderKey1");
        assertEquals(reply.getResultsMap().size(), 3);
        reply.getResultsMap().forEach((k, v) -> assertEquals(v.getFanout(), 0));
    }

    @Test(groups = "integration")
    public void batchDist() {
        when(receiverManager.get(MqttBroker)).thenReturn(mqttBroker);
        when(mqttBroker.open("batch1")).thenReturn(writer1);
        when(receiverManager.get(InboxService)).thenReturn(inboxBroker);
        when(inboxBroker.open("batch2")).thenReturn(writer2);
        when(writer1.deliver(any())).thenAnswer(answer(DeliveryResult.Code.OK));
        when(writer2.deliver(any())).thenAnswer(answer(DeliveryResult.Code.OK));

        match(tenantA, "/a/1", MqttBroker, "inbox1", "batch1");
        match(tenantA, "/a/2", MqttBroker, "inbox1", "batch1");
        match(tenantA, "/a/2", MqttBroker, "inbox3", "batch1");
        match(tenantA, "/a/3", InboxService, "inbox2", "batch2");
        match(tenantA, "/a/4", InboxService, "inbox2", "batch2");

        TenantDistReply reply = tenantDist(tenantA,
            List.of(
                TopicMessagePack.newBuilder()
                    .setTopic("/a/1")
                    .addMessage(toMsg(tenantA, AT_MOST_ONCE, copyFromUtf8("Hello")))
                    .build(),
                TopicMessagePack.newBuilder()
                    .setTopic("/a/2")
                    .addMessage(toMsg(tenantA, AT_MOST_ONCE, copyFromUtf8("Hello")))
                    .build(),
                TopicMessagePack.newBuilder()
                    .setTopic("/a/3")
                    .addMessage(toMsg(tenantA, AT_MOST_ONCE, copyFromUtf8("Hello")))
                    .build(),
                TopicMessagePack.newBuilder()
                    .setTopic("/a/4")
                    .addMessage(toMsg(tenantA, AT_MOST_ONCE, copyFromUtf8("Hello")))
                    .build()), "orderKey1");

        assertEquals(reply.getResultsMap().get("/a/1").getFanout(), 1);
        assertEquals(reply.getResultsMap().get("/a/2").getFanout(), 2);
        assertEquals(reply.getResultsMap().get("/a/3").getFanout(), 1);
        assertEquals(reply.getResultsMap().get("/a/4").getFanout(), 1);

        unmatch(tenantA, "/a/1", MqttBroker, "inbox1", "batch1");
        unmatch(tenantA, "/a/2", MqttBroker, "inbox1", "batch1");
        unmatch(tenantA, "/a/2", MqttBroker, "inbox3", "batch1");
        unmatch(tenantA, "/a/3", InboxService, "inbox2", "batch2");
        unmatch(tenantA, "/a/4", InboxService, "inbox2", "batch2");
    }

    private TopicMessagePack.PublisherPack toMsg(String tenantId, QoS qos, ByteString payload) {
        return TopicMessagePack.PublisherPack.newBuilder()
            .setPublisher(ClientInfo.newBuilder()
                .setTenantId(tenantId)
                .setType(MQTT_TYPE_VALUE)
                .putMetadata(MQTT_PROTOCOL_VER_KEY, MQTT_PROTOCOL_VER_3_1_1_VALUE)
                .putMetadata(MQTT_USER_ID_KEY, "testUser")
                .putMetadata(MQTT_CLIENT_ID_KEY, "testClientId")
                .putMetadata(MQTT_CLIENT_ADDRESS_KEY, "127.0.0.1:8080")
                .build())
            .addMessage(Message.newBuilder()
                .setMessageId(ThreadLocalRandom.current().nextInt())
                .setPubQoS(qos)
                .setPayload(payload)
                .setTimestamp(System.currentTimeMillis())
                .build())
            .build();
    }
}
