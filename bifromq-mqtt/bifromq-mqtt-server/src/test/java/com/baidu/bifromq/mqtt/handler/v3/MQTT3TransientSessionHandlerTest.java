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

package com.baidu.bifromq.mqtt.handler.v3;


import static com.baidu.bifromq.mqtt.handler.MQTTSessionIdUtil.userSessionId;
import static com.baidu.bifromq.mqtt.utils.MessageIdUtil.messageId;
import static com.baidu.bifromq.plugin.eventcollector.EventType.DISCARD;
import static com.baidu.bifromq.plugin.eventcollector.EventType.INVALID_TOPIC;
import static com.baidu.bifromq.plugin.eventcollector.EventType.INVALID_TOPIC_FILTER;
import static com.baidu.bifromq.plugin.eventcollector.EventType.MALFORMED_TOPIC;
import static com.baidu.bifromq.plugin.eventcollector.EventType.MALFORMED_TOPIC_FILTER;
import static com.baidu.bifromq.plugin.eventcollector.EventType.MSG_RETAINED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.MSG_RETAINED_ERROR;
import static com.baidu.bifromq.plugin.eventcollector.EventType.PROTOCOL_VIOLATION;
import static com.baidu.bifromq.plugin.eventcollector.EventType.PUB_ACKED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.PUB_RECED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.PUB_REC_DROPPED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS0_DIST_ERROR;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS0_DROPPED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS0_PUSHED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS1_CONFIRMED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS1_DIST_ERROR;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS1_DROPPED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS1_PUSHED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS2_CONFIRMED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS2_DIST_ERROR;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS2_DROPPED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS2_PUSHED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS2_RECEIVED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.RETAIN_MSG_CLEARED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.SERVER_BUSY;
import static com.baidu.bifromq.plugin.eventcollector.EventType.SUB_ACKED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.TOO_LARGE_UNSUBSCRIPTION;
import static com.baidu.bifromq.plugin.eventcollector.EventType.UNSUB_ACKED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.UNSUB_ACTION_DISALLOW;
import static com.baidu.bifromq.plugin.settingprovider.Setting.MsgPubPerSec;
import static com.baidu.bifromq.retain.rpc.proto.RetainReply.Result.CLEARED;
import static com.baidu.bifromq.retain.rpc.proto.RetainReply.Result.ERROR;
import static com.baidu.bifromq.retain.rpc.proto.RetainReply.Result.RETAINED;
import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_CHANNEL_ID_KEY;
import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_CLIENT_ADDRESS_KEY;
import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_CLIENT_ID_KEY;
import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_PROTOCOL_VER_KEY;
import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_TYPE_VALUE;
import static com.baidu.bifromq.type.MQTTClientInfoConstants.MQTT_USER_ID_KEY;
import static io.netty.handler.codec.mqtt.MqttMessageType.PUBREL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.baidu.bifromq.dist.client.DistResult;
import com.baidu.bifromq.mqtt.handler.BaseSessionHandlerTest;
import com.baidu.bifromq.mqtt.handler.ChannelAttrs;
import com.baidu.bifromq.mqtt.handler.TenantSettings;
import com.baidu.bifromq.mqtt.session.MQTTSessionContext;
import com.baidu.bifromq.mqtt.utils.MQTTMessageUtils;
import com.baidu.bifromq.plugin.authprovider.type.CheckResult;
import com.baidu.bifromq.plugin.authprovider.type.Granted;
import com.baidu.bifromq.plugin.authprovider.type.MQTTAction;
import com.baidu.bifromq.plugin.eventcollector.EventType;
import com.baidu.bifromq.type.ClientInfo;
import com.baidu.bifromq.type.Message;
import com.baidu.bifromq.type.QoS;
import com.baidu.bifromq.type.TopicMessagePack;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttUnsubAckMessage;
import io.netty.handler.codec.mqtt.MqttUnsubscribeMessage;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Slf4j
public class MQTT3TransientSessionHandlerTest extends BaseSessionHandlerTest {

    private MQTT3TransientSessionHandler transientSessionHandler;
    private boolean shouldCleanSubs = false;

    @BeforeMethod(alwaysRun = true)
    public void setup(Method method) {
        super.setup(method);
        int keepAlive = 2;
        sessionContext = MQTTSessionContext.builder()
            .serverId(serverId)
            .ticker(testTicker)
            .defaultKeepAliveTimeSeconds(keepAlive)
            .distClient(distClient)
            .retainClient(retainClient)
            .authProvider(authProvider)
            .localDistService(localDistService)
            .localSessionRegistry(localSessionRegistry)
            .sessionDictClient(sessionDictClient)
            .eventCollector(eventCollector)
            .resourceThrottler(resourceThrottler)
            .settingProvider(settingProvider)
            .build();
        // common mocks
        mockSettings();
        ChannelDuplexHandler sessionHandlerAdder = new ChannelDuplexHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                super.channelActive(ctx);
                ctx.pipeline().addLast(MQTT3TransientSessionHandler.builder()
                    .settings(new TenantSettings(tenantId, settingProvider))
                    .userSessionId(userSessionId(clientInfo))
                    .keepAliveTimeSeconds(120)
                    .clientInfo(clientInfo)
                    .willMessage(null)
                    .ctx(ctx)
                    .build());
                ctx.pipeline().remove(this);
            }
        };
        mockSessionReg();
        channel = new EmbeddedChannel(true, true, new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.attr(ChannelAttrs.MQTT_SESSION_CTX).set(sessionContext);
                ch.attr(ChannelAttrs.PEER_ADDR).set(new InetSocketAddress(remoteIp, remotePort));
                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast(new ChannelTrafficShapingHandler(512 * 1024, 512 * 1024));
                pipeline.addLast(MqttDecoder.class.getName(), new MqttDecoder(256 * 1024));
                pipeline.addLast(sessionHandlerAdder);
            }
        });
        //channel.freezeTime();
        transientSessionHandler = (MQTT3TransientSessionHandler) channel.pipeline().last();
    }

    @SneakyThrows
    @AfterMethod(alwaysRun = true)
    public void tearDown(Method method) {
        if (shouldCleanSubs) {
            when(distClient.unmatch(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
            channel.close();
            verify(localDistService, atLeast(1))
                .unmatch(anyLong(), anyString(), any());
        } else {
            channel.close();
        }
        shouldCleanSubs = false;
        super.tearDown(method);
    }


    @Test
    public void handleConnect() {
        assertTrue(channel.isOpen());

        channel.writeInbound(MQTTMessageUtils.mqttConnectMessage(true));
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runPendingTasks();
        assertFalse(channel.isOpen());
        verifyEvent(PROTOCOL_VIOLATION);
    }

//  =============================================== sub & unSub ======================================================

    @Test
    public void transientQoS0Sub() {
        mockCheckPermission(true);
        mockDistMatch(true);
        mockRetainMatch();
        int[] qos = {0, 0, 0};
        MqttSubscribeMessage subMessage = MQTTMessageUtils.qoSMqttSubMessages(qos);
        channel.writeInbound(subMessage);
        MqttSubAckMessage subAckMessage = channel.readOutbound();
        verifySubAck(subAckMessage, qos);
        verifyEvent(SUB_ACKED);
        shouldCleanSubs = true;
    }

    @Test
    public void transientQoS1Sub() {
        mockCheckPermission(true);
        mockDistMatch(true);
        mockRetainMatch();
        int[] qos = {1, 1, 1};
        MqttSubscribeMessage subMessage = MQTTMessageUtils.qoSMqttSubMessages(qos);
        channel.writeInbound(subMessage);
        MqttSubAckMessage subAckMessage = channel.readOutbound();
        verifySubAck(subAckMessage, qos);
        verifyEvent(SUB_ACKED);
        shouldCleanSubs = true;
    }

    @Test
    public void transientQoS2Sub() {
        mockCheckPermission(true);
        mockDistMatch(true);
        mockRetainMatch();
        int[] qos = {2, 2, 2};
        MqttSubscribeMessage subMessage = MQTTMessageUtils.qoSMqttSubMessages(qos);
        channel.writeInbound(subMessage);
        MqttSubAckMessage subAckMessage = channel.readOutbound();
        verifySubAck(subAckMessage, qos);
        verifyEvent(SUB_ACKED);
        shouldCleanSubs = true;
    }

    @Test
    public void transientMixedSub() {
        mockCheckPermission(true);
        mockDistMatch(true);
        mockRetainMatch();
        int[] qos = {0, 1, 2};
        MqttSubscribeMessage subMessage = MQTTMessageUtils.qoSMqttSubMessages(qos);
        channel.writeInbound(subMessage);
        MqttSubAckMessage subAckMessage = channel.readOutbound();
        verifySubAck(subAckMessage, qos);
        verifyEvent(SUB_ACKED);
        shouldCleanSubs = true;
    }

    @Test
    public void transientMixedSubWithDistSubFailed() {
        mockCheckPermission(true);
        mockDistMatch("testTopic0", true);
        mockDistMatch("testTopic1", true);
        mockDistMatch("testTopic2", false);
        mockRetainMatch();
        int[] qos = {0, 1, 2};
        MqttSubscribeMessage subMessage = MQTTMessageUtils.qoSMqttSubMessages(qos);
        channel.writeInbound(subMessage);
        MqttSubAckMessage subAckMessage = channel.readOutbound();
        verifySubAck(subAckMessage, new int[] {0, 1, 128});
        verifyEvent(SUB_ACKED);
        shouldCleanSubs = true;
    }


    @Test
    public void transientUnSub() {
        mockCheckPermission(true);
        mockDistUnMatch(true);
        channel.writeInbound(MQTTMessageUtils.qoSMqttUnSubMessages(3));
        MqttUnsubAckMessage unsubAckMessage = channel.readOutbound();
        Assert.assertNotNull(unsubAckMessage);
        verifyEvent(UNSUB_ACKED);
    }

    @Test
    public void transientMixedUnSubWithDistUnSubFailed() {
        mockCheckPermission(true);
        mockDistUnMatch(true, false, true);
        channel.writeInbound(MQTTMessageUtils.qoSMqttUnSubMessages(3));
        MqttUnsubAckMessage unsubAckMessage = channel.readOutbound();
        Assert.assertNotNull(unsubAckMessage);
        verifyEvent(UNSUB_ACKED);
    }

    @Test
    public void unSubWithEmptyTopicList() {
        MqttUnsubscribeMessage unSubMessage = MQTTMessageUtils.badMqttUnSubMessageWithoutTopic();
        channel.writeInbound(unSubMessage);
        channel.advanceTimeBy(6000, TimeUnit.MILLISECONDS);
        channel.writeInbound();
        verifyEvent(PROTOCOL_VIOLATION);
    }

    @Test
    public void unSubWithTooLargeTopicList() {
        MqttUnsubscribeMessage unSubMessage = MQTTMessageUtils.qoSMqttUnSubMessages(100);
        channel.writeInbound(unSubMessage);
        channel.advanceTimeBy(6000, TimeUnit.MILLISECONDS);
        channel.writeInbound();
        verifyEvent(TOO_LARGE_UNSUBSCRIPTION);
    }

    @Test
    public void unSubWithMalformedTopic() {
        MqttUnsubscribeMessage unSubMessage = MQTTMessageUtils.topicMqttUnSubMessage("/topic\u0000");
        channel.writeInbound(unSubMessage);
        channel.advanceTimeBy(6000, TimeUnit.MILLISECONDS);
        channel.writeInbound();
        verifyEvent(MALFORMED_TOPIC_FILTER);
    }

    @Test
    public void unSubWithInvalidTopic() {
        MqttUnsubscribeMessage unSubMessage = MQTTMessageUtils.invalidTopicMqttUnSubMessage();
        channel.writeInbound(unSubMessage);
        channel.advanceTimeBy(6000, TimeUnit.MILLISECONDS);
        channel.writeInbound();
        verifyEvent(INVALID_TOPIC_FILTER, UNSUB_ACKED);
    }

    @Test
    public void unSubWithAuthFailed() {
        mockCheckPermission(false);
        channel.writeInbound(MQTTMessageUtils.qoSMqttUnSubMessages(3));
        MqttUnsubAckMessage unsubAckMessage = channel.readOutbound();
        Assert.assertNotNull(unsubAckMessage);
        verifyEvent(UNSUB_ACTION_DISALLOW, UNSUB_ACTION_DISALLOW, UNSUB_ACTION_DISALLOW,
            UNSUB_ACKED);
    }


//  =============================================== C2SPub ============================================================

    @Test
    public void handleQoS0Pub() {
        mockCheckPermission(true);
        mockDistDist(true);
        when(distClient.pub(anyLong(), anyString(), any(Message.class), any(ClientInfo.class))).thenReturn(
            CompletableFuture.completedFuture(DistResult.OK));
        assertTrue(channel.isOpen());
        MqttPublishMessage message = MQTTMessageUtils.publishQoS0Message(topic, 123);
        channel.writeInbound(message);
        verify(distClient, times(1)).pub(anyLong(), eq(topic), any(Message.class), eq(clientInfo));
    }

    @Test
    public void handleQoS0PubDistError() {
        mockCheckPermission(true);
        mockDistDist(false);
        assertTrue(channel.isOpen());

        MqttPublishMessage message = MQTTMessageUtils.publishQoS0Message(topic, 123);
        channel.writeInbound(message);
        verify(distClient, times(1)).pub(anyLong(), eq(topic), any(Message.class), eq(clientInfo));
        verifyEvent(QOS0_DIST_ERROR);
    }

    @Test
    public void handleQoS0PubDistBackPressured() {
        mockCheckPermission(true);
        mockDistBackPressure();
        assertTrue(channel.isOpen());

        MqttPublishMessage message = MQTTMessageUtils.publishQoS0Message(topic, 123);
        channel.writeInbound(message);
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        // the channel is still open, but the message is dropped
        assertTrue(channel.isOpen());
        verifyEvent(QOS0_DIST_ERROR, SERVER_BUSY);
    }


    @Test
    public void handleQoS1Pub() {
        mockCheckPermission(true);
        mockDistDist(true);
        assertTrue(channel.isOpen());

        MqttPublishMessage message = MQTTMessageUtils.publishQoS1Message(topic, 123);
        channel.writeInbound(message);
        MqttMessage ackMessage = channel.readOutbound();
        assertEquals(((MqttMessageIdVariableHeader) ackMessage.variableHeader()).messageId(), 123);
        verifyEvent(PUB_ACKED);
    }

    @Test
    public void handleQoS1PubDistError() {
        mockCheckPermission(true);
        mockDistDist(false);
        assertTrue(channel.isOpen());

        MqttPublishMessage message = MQTTMessageUtils.publishQoS1Message(topic, 123);
        channel.writeInbound(message);
        MqttMessage ackMessage = channel.readOutbound();
        // dist error still pub ack, record error log
        assertEquals(((MqttMessageIdVariableHeader) ackMessage.variableHeader()).messageId(), 123);
        verifyEvent(QOS1_DIST_ERROR, PUB_ACKED);
    }

    @Test
    public void handleQoS1PubDistBackPressured() {
        mockCheckPermission(true);
        mockDistBackPressure();
        assertTrue(channel.isOpen());

        MqttPublishMessage message = MQTTMessageUtils.publishQoS1Message(topic, 123);
        channel.writeInbound(message);
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        // the channel is still open, but the message is dropped
        assertTrue(channel.isOpen());
        verifyEvent(QOS1_DIST_ERROR, SERVER_BUSY);
    }

    @Test
    public void handleQoS2Pub() {
        mockCheckPermission(true);
        mockDistDist(true);
        assertTrue(channel.isOpen());

        // publish to channel
        MqttPublishMessage message = MQTTMessageUtils.publishQoS2Message(topic, 123);
        channel.writeInbound(message);
        MqttMessage mqttMessage = channel.readOutbound();
        assertEquals(mqttMessage.fixedHeader().messageType(), MqttMessageType.PUBREC);
        assertEquals(((MqttMessageIdVariableHeader) mqttMessage.variableHeader()).messageId(), 123);
        // publish release to channel
        channel.writeInbound(MQTTMessageUtils.publishRelMessage(123));
        mqttMessage = channel.readOutbound();
        assertEquals(mqttMessage.fixedHeader().messageType(), MqttMessageType.PUBCOMP);
        assertEquals(((MqttMessageIdVariableHeader) mqttMessage.variableHeader()).messageId(), 123);
        verifyEvent(PUB_RECED);
    }

    @Test
    public void handleQoS2PubDistError() {
        mockCheckPermission(true);
        mockDistDist(false);
        assertTrue(channel.isOpen());

        // publish to channel
        MqttPublishMessage message = MQTTMessageUtils.publishQoS2Message(topic, 123);
        channel.writeInbound(message);
        // dist error still pub rec, record error log
        MqttMessage mqttMessage = channel.readOutbound();
        assertEquals(mqttMessage.fixedHeader().messageType(), MqttMessageType.PUBREC);
        assertEquals(((MqttMessageIdVariableHeader) mqttMessage.variableHeader()).messageId(), 123);
        // publish release to channel
        channel.writeInbound(MQTTMessageUtils.publishRelMessage(123));
        mqttMessage = channel.readOutbound();
        assertEquals(mqttMessage.fixedHeader().messageType(), MqttMessageType.PUBCOMP);
        assertEquals(((MqttMessageIdVariableHeader) mqttMessage.variableHeader()).messageId(), 123);
        verifyEvent(QOS2_DIST_ERROR, PUB_RECED);
    }

    @Test
    public void handleQoS2PubDistBackPressured() {
        mockCheckPermission(true);
        mockDistBackPressure();
        assertTrue(channel.isOpen());

        MqttPublishMessage message = MQTTMessageUtils.publishQoS2Message(topic, 123);
        channel.writeInbound(message);
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        // the channel is still open, but the message is dropped
        assertTrue(channel.isOpen());
        verifyEvent(QOS2_DIST_ERROR, SERVER_BUSY);
    }

    @Test
    public void qoS2PubWithUnWritable() {
        CompletableFuture<DistResult> distResult = new CompletableFuture<>();
        when(authProvider.checkPermission(any(ClientInfo.class), any(MQTTAction.class))).thenReturn(
            CompletableFuture.completedFuture(
                CheckResult.newBuilder().setGranted(Granted.newBuilder().build()).build()));
        when(distClient.pub(anyLong(), anyString(), any(), any(ClientInfo.class))).thenReturn(distResult);
        assertTrue(channel.isOpen());
        channel.writeInbound(MQTTMessageUtils.publishQoS2Message("testTopic", 123));

        // make channel unWritable and drop PubRec
        channel.writeOneOutbound(MQTTMessageUtils.largeMqttMessage(300 * 1024));
        channel.writeOneOutbound(MQTTMessageUtils.largeMqttMessage(300 * 1024));
        assertFalse(channel.isWritable());
        distResult.complete(DistResult.OK);
        channel.runPendingTasks();
        verifyEvent(PUB_REC_DROPPED);

        // flush channel
        channel.flush();
        channel.readOutbound();
        channel.readOutbound();
        assertTrue(channel.isWritable());

        // client did not receive PubRec, resend pub and receive PubRec
        channel.writeInbound(MQTTMessageUtils.publishQoS2Message(topic, 123));
        channel.runPendingTasks();
        MqttMessage mqttMessage = channel.readOutbound();
        assertEquals(mqttMessage.fixedHeader().messageType(), MqttMessageType.PUBREC);
        assertEquals(((MqttMessageIdVariableHeader) mqttMessage.variableHeader()).messageId(), 123);

        // continue to publish PubRel
        channel.writeInbound(MQTTMessageUtils.publishRelMessage(123));
        mqttMessage = channel.readOutbound();
        assertEquals(mqttMessage.fixedHeader().messageType(), MqttMessageType.PUBCOMP);
        assertEquals(((MqttMessageIdVariableHeader) mqttMessage.variableHeader()).messageId(), 123);
        verifyEvent(PUB_REC_DROPPED, PUB_RECED);
    }

    @Test
    public void malformedTopic() {
        assertTrue(channel.isOpen());

        MqttPublishMessage publishMessage = MQTTMessageUtils.publishQoS0Message("topic\u0000", 123);
        channel.writeInbound(publishMessage);
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runPendingTasks();
        assertFalse(channel.isActive());
        verifyEvent(MALFORMED_TOPIC);
    }

    @Test
    public void invalidTopic() {
        assertTrue(channel.isOpen());
        MqttPublishMessage publishMessage = MQTTMessageUtils.publishQoS0Message("$share/g/testTopic", 123);
        channel.writeInbound(publishMessage);
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runPendingTasks();
        assertFalse(channel.isActive());
        verifyEvent(INVALID_TOPIC);
    }

    @Test
    public void pubTooFast() {
        mockCheckPermission(true);
        mockDistDist(true);
        when(settingProvider.provide(eq(MsgPubPerSec), anyString())).thenReturn(1);
        assertTrue(channel.isOpen());
        channel.pipeline().removeLast();
        // add new MQTT3TransientSessionHandler with MsgPubPerSec = 1
        channel.pipeline().addLast(new ChannelDuplexHandler() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                super.channelActive(ctx);
                ctx.pipeline().addLast(MQTT3TransientSessionHandler.builder()
                    .settings(new TenantSettings(tenantId, settingProvider))
                    .userSessionId(userSessionId(clientInfo))
                    .keepAliveTimeSeconds(120)
                    .clientInfo(clientInfo)
                    .willMessage(null)
                    .ctx(ctx)
                    .build());
                ctx.pipeline().remove(this);
            }
        });

        MqttPublishMessage publishMessage = MQTTMessageUtils.publishQoS1Message("testTopic", 1);
        MqttPublishMessage publishMessage2 = MQTTMessageUtils.publishQoS1Message("testTopic", 2);
        channel.writeInbound(publishMessage);
        channel.writeInbound(publishMessage2);
        MqttMessage ackMessage = channel.readOutbound();
        assertEquals(((MqttMessageIdVariableHeader) ackMessage.variableHeader()).messageId(), 1);
        verifyEvent(PUB_ACKED, DISCARD);
    }


//  =============================================== retain ============================================================

    @Test
    public void qoS1PubRetain() {
        mockCheckPermission(true);
        mockDistDist(true);
        mockRetainPipeline(RETAINED);
        MqttPublishMessage publishMessage = MQTTMessageUtils.publishRetainQoS1Message(topic, 123);
        channel.writeInbound(publishMessage);
        verifyEvent(MSG_RETAINED, PUB_ACKED);
    }

    @Test
    public void qoS1PubRetainClear() {
        mockCheckPermission(true);
        mockDistDist(true);
        mockRetainPipeline(CLEARED);
        MqttPublishMessage publishMessage = MQTTMessageUtils.publishRetainQoS1Message(topic, 123);
        channel.writeInbound(publishMessage);
        verifyEvent(RETAIN_MSG_CLEARED, PUB_ACKED);
    }

    @Test
    public void qoS1PubRetainFailed() {
        mockCheckPermission(true);
        mockDistDist(true);
        mockRetainPipeline(ERROR);
        MqttPublishMessage publishMessage = MQTTMessageUtils.publishRetainQoS1Message(topic, 123);
        channel.writeInbound(publishMessage);
        verifyEvent(MSG_RETAINED_ERROR, PUB_ACKED);
    }

    @Test
    public void qoS2PubRetainFailed() {
        mockCheckPermission(true);
        mockDistDist(true);
        mockRetainPipeline(ERROR);
        MqttPublishMessage publishMessage = MQTTMessageUtils.publishRetainQoS2Message(topic, 123);
        channel.writeInbound(publishMessage);
        verifyEvent(MSG_RETAINED_ERROR, PUB_RECED);
    }


//  =============================================== S2CPub ============================================================

    @Test
    public void qos0Pub() {
        mockCheckPermission(true);
        mockDistMatch(true);
        transientSessionHandler.subscribe(System.nanoTime(), topicFilter, QoS.AT_MOST_ONCE);
        channel.runPendingTasks();

        transientSessionHandler.publish(matchInfo(topicFilter), s2cMessageList(topic, 5, QoS.AT_MOST_ONCE));
        channel.runPendingTasks();
        for (int i = 0; i < 5; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.AT_MOST_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
        }
        verifyEvent(QOS0_PUSHED, QOS0_PUSHED, QOS0_PUSHED, QOS0_PUSHED, QOS0_PUSHED);
    }

    @Test
    public void qos0PubExceedBufferCapacity() {
        mockCheckPermission(true);
        mockDistMatch(true);
        transientSessionHandler.subscribe(System.nanoTime(), topicFilter, QoS.AT_MOST_ONCE);
        channel.runPendingTasks();

        channel.writeOneOutbound(MQTTMessageUtils.largeMqttMessage(300 * 1024));
        List<ByteBuffer> payloads = s2cMessagesPayload(1, 32 * 1024);
        transientSessionHandler.publish(matchInfo(topicFilter), s2cMessageList(topic, payloads, QoS.AT_MOST_ONCE));
        channel.runPendingTasks();
        MqttPublishMessage message = channel.readOutbound();
        assertNull(message);
        verifyEvent(QOS0_DROPPED);
    }

    @Test
    public void qos1PubAndAck() {
        mockCheckPermission(true);
        mockDistMatch(true);
        transientSessionHandler.subscribe(System.nanoTime(), topicFilter, QoS.AT_LEAST_ONCE);
        channel.runPendingTasks();

        int messageCount = 3;
        transientSessionHandler.publish(matchInfo(topicFilter), s2cMessageList(topic, messageCount, QoS.AT_LEAST_ONCE));
        channel.runPendingTasks();
        // s2c pub received and ack
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.AT_LEAST_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
            channel.writeInbound(MQTTMessageUtils.pubAckMessage(message.variableHeader().packetId()));
        }
        verifyEvent(QOS1_PUSHED, QOS1_PUSHED, QOS1_PUSHED, QOS1_CONFIRMED, QOS1_CONFIRMED, QOS1_CONFIRMED);
    }

    @Test
    public void qos1PubExceedBufferCapacity() {
        mockCheckPermission(true);
        mockDistMatch(true);
        transientSessionHandler.subscribe(System.nanoTime(), topicFilter, QoS.AT_LEAST_ONCE);
        channel.runPendingTasks();

        channel.writeOneOutbound(MQTTMessageUtils.largeMqttMessage(300 * 1024));
        List<ByteBuffer> payloads = s2cMessagesPayload(1, 32 * 1024);
        transientSessionHandler.publish(matchInfo(topicFilter), s2cMessageList(topic, payloads, QoS.AT_LEAST_ONCE));
        channel.runPendingTasks();
        MqttPublishMessage message = channel.readOutbound();
        assertNull(message);
        verifyEvent();
    }

    @Test
    public void qoS1PubAndNoAck() {
        mockCheckPermission(true);
        mockDistMatch(true);
        transientSessionHandler.subscribe(System.nanoTime(), topicFilter, QoS.AT_LEAST_ONCE);
        channel.runPendingTasks();

        int messageCount = 3;
        transientSessionHandler.publish(matchInfo(topicFilter), s2cMessageList(topic, messageCount, QoS.AT_LEAST_ONCE));
        channel.runPendingTasks();
        // s2c pub received and not ack
        List<Integer> packetIds = Lists.newArrayList();
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.AT_LEAST_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
            packetIds.add(message.variableHeader().packetId());
        }
        // resent once
        testTicker.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.flushOutbound();
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.AT_LEAST_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
            assertEquals(message.variableHeader().packetId(), (int) packetIds.get(i));
        }
        // resent twice
        testTicker.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.flushOutbound();
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.AT_LEAST_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
            assertEquals(message.variableHeader().packetId(), (int) packetIds.get(i));
        }
        // resent three times
        testTicker.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.flushOutbound();
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.AT_LEAST_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
            assertEquals(message.variableHeader().packetId(), (int) packetIds.get(i));
        }

        verifyEvent(QOS1_PUSHED, QOS1_PUSHED, QOS1_PUSHED, QOS1_PUSHED, QOS1_PUSHED, QOS1_PUSHED,
            QOS1_PUSHED, QOS1_PUSHED, QOS1_PUSHED, QOS1_PUSHED, QOS1_PUSHED, QOS1_PUSHED);
    }

    @Test
    public void qoS1PubAndInboxOverflow() {
        mockCheckPermission(true);
        mockDistMatch(true);
        transientSessionHandler.subscribe(System.nanoTime(), topicFilter, QoS.AT_LEAST_ONCE);
        channel.runPendingTasks();

        int messageCount = 1000;
        int overFlowCount = 10;
        transientSessionHandler.publish(matchInfo(topicFilter), s2cMessageList(topic, messageCount, QoS.AT_LEAST_ONCE));
        channel.runPendingTasks();
        transientSessionHandler.publish(matchInfo(topicFilter),
            s2cMessageList(topic, overFlowCount, QoS.AT_LEAST_ONCE));
        channel.runPendingTasks();
        // s2c pub received and not ack
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertNotNull(message);
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.AT_LEAST_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
        }

        EventType[] eventTypes = new EventType[messageCount + overFlowCount];
        for (int i = 0; i < messageCount; i++) {
            eventTypes[i] = QOS1_PUSHED;
        }
        for (int i = messageCount; i < messageCount + overFlowCount; i++) {
            eventTypes[i] = QOS1_DROPPED;
        }
        verifyEvent(eventTypes);
    }

    @Test
    public void qoS2PubAndRel() {
        mockCheckPermission(true);
        mockDistMatch(true);
        transientSessionHandler.subscribe(System.nanoTime(), topicFilter, QoS.EXACTLY_ONCE);
        channel.runPendingTasks();

        transientSessionHandler.publish(matchInfo(topicFilter), s2cMessageList(topic, 1, QoS.EXACTLY_ONCE));
        channel.runPendingTasks();
        // s2c pub received and rec
        MqttPublishMessage message = channel.readOutbound();
        assertEquals(message.fixedHeader().qosLevel().value(), QoS.EXACTLY_ONCE_VALUE);
        assertEquals(message.variableHeader().topicName(), topic);
        channel.writeInbound(MQTTMessageUtils.publishRecMessage(message.variableHeader().packetId()));
        // pubRel received and comp
        MqttMessage pubRel = channel.readOutbound();
        assertEquals(pubRel.fixedHeader().messageType(), PUBREL);
        channel.writeInbound(MQTTMessageUtils.publishCompMessage(
            ((MqttMessageIdVariableHeader) pubRel.variableHeader()).messageId()));
        verifyEvent(QOS2_PUSHED, QOS2_RECEIVED, QOS2_CONFIRMED);
    }

    @Test
    public void qoS2PubExceedBufferCapacity() {
        mockCheckPermission(true);
        mockDistMatch(true);
        transientSessionHandler.subscribe(System.nanoTime(), topicFilter, QoS.EXACTLY_ONCE);
        channel.runPendingTasks();

        channel.writeOneOutbound(MQTTMessageUtils.largeMqttMessage(300 * 1024));
        List<ByteBuffer> payloads = s2cMessagesPayload(1, 32 * 1024);
        transientSessionHandler.publish(matchInfo(topicFilter), s2cMessageList(topic, payloads, QoS.EXACTLY_ONCE));
        channel.runPendingTasks();
        MqttPublishMessage message = channel.readOutbound();
        assertNull(message);
        verifyEvent();
    }

    @Test
    public void qoS2PubAndNoRec() {
        mockCheckPermission(true);
        mockDistMatch(true);
        transientSessionHandler.subscribe(System.nanoTime(), topicFilter, QoS.EXACTLY_ONCE);
        channel.runPendingTasks();

        int messageCount = 1;
        transientSessionHandler.publish(matchInfo(topicFilter), s2cMessageList(topic, messageCount, QoS.EXACTLY_ONCE));
        channel.runPendingTasks();
        // s2c pub received and not ack
        List<Integer> packetIds = Lists.newArrayList();
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.EXACTLY_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
            packetIds.add(message.variableHeader().packetId());
        }
        // resent once
        testTicker.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.flushOutbound();
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.EXACTLY_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
            assertEquals(message.variableHeader().packetId(), (int) packetIds.get(i));
        }
        // resent twice
        testTicker.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.flushOutbound();
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.EXACTLY_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
            assertEquals(message.variableHeader().packetId(), (int) packetIds.get(i));
        }
        // resent three times
        testTicker.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.advanceTimeBy(11, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.flushOutbound();
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.EXACTLY_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
            assertEquals(message.variableHeader().packetId(), (int) packetIds.get(i));
        }
        verifyEvent(QOS2_PUSHED, QOS2_PUSHED, QOS2_PUSHED, QOS2_PUSHED);
    }

    @Test
    public void qoS2PubWithSameSourcePacketId() {
        mockCheckPermission(true);
        mockDistMatch(true);
        transientSessionHandler.subscribe(System.nanoTime(), "#", QoS.EXACTLY_ONCE);
        channel.runPendingTasks();

        int messageCount = 2;
        List<ByteBuffer> payloads = s2cMessagesPayload(messageCount, 1);
        List<TopicMessagePack.PublisherPack> messagesFromClient1 = Lists.newArrayList();
        List<TopicMessagePack.PublisherPack> messagesFromClient2 = Lists.newArrayList();
        for (ByteBuffer payload : payloads) {
            // messages with duplicated messageId
            messagesFromClient1.add(TopicMessagePack.PublisherPack.newBuilder()
                .setPublisher(ClientInfo.newBuilder()
                    .setTenantId(tenantId)
                    .setType(MQTT_TYPE_VALUE)
                    .putMetadata(MQTT_PROTOCOL_VER_KEY, "3.1.1")
                    .putMetadata(MQTT_USER_ID_KEY, "testUser")
                    .putMetadata(MQTT_CHANNEL_ID_KEY, "client1")
                    .putMetadata(MQTT_CLIENT_ID_KEY, "channel1")
                    .putMetadata(MQTT_CLIENT_ADDRESS_KEY, "127.0.0.1:11111")
                    .build()
                )
                .addMessage(Message.newBuilder()
                    .setMessageId(messageId(1, 0))
                    .setPayload(ByteString.copyFrom(payload.duplicate()))
                    .setTimestamp(System.currentTimeMillis())
                    .setPubQoS(QoS.EXACTLY_ONCE)
                    .build())
                .build());
            messagesFromClient2.add(TopicMessagePack.PublisherPack.newBuilder()
                .setPublisher(ClientInfo.newBuilder()
                    .setTenantId(tenantId)
                    .setType(MQTT_TYPE_VALUE)
                    .putMetadata(MQTT_PROTOCOL_VER_KEY, "3.1.1")
                    .putMetadata(MQTT_USER_ID_KEY, "testUser")
                    .putMetadata(MQTT_CHANNEL_ID_KEY, "client2")
                    .putMetadata(MQTT_CLIENT_ID_KEY, "channel2")
                    .putMetadata(MQTT_CLIENT_ADDRESS_KEY, "127.0.0.1:22222")
                    .build()
                )
                .addMessage(Message.newBuilder()
                    .setMessageId(messageId(1, 0))
                    .setPayload(ByteString.copyFrom(payload.duplicate()))
                    .setTimestamp(System.currentTimeMillis())
                    .setPubQoS(QoS.EXACTLY_ONCE)
                    .build())
                .build()
            );
        }
        transientSessionHandler.publish(matchInfo("#"), Lists.newArrayList(
            TopicMessagePack.newBuilder()
                .setTopic(topic)
                .addAllMessage(messagesFromClient1)
                .build(),
            TopicMessagePack.newBuilder()
                .setTopic(topic + "2")
                .addAllMessage(messagesFromClient2)
                .build()
        ));
        channel.runPendingTasks();
        // should two messages from client1 and client2
        MqttPublishMessage message1_1 = channel.readOutbound();
        assertEquals(message1_1.fixedHeader().qosLevel().value(), QoS.EXACTLY_ONCE_VALUE);
        assertEquals(message1_1.variableHeader().topicName(), topic);
        MqttPublishMessage message1_2 = channel.readOutbound();
        assertEquals(message1_2.fixedHeader().qosLevel().value(), QoS.EXACTLY_ONCE_VALUE);
        assertEquals(message1_2.variableHeader().topicName(), topic + "2");

        verifyEvent(QOS2_DROPPED, QOS2_PUSHED, QOS2_PUSHED);
    }

}
