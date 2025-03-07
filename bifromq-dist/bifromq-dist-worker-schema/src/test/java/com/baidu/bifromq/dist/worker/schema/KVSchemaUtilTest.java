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

package com.baidu.bifromq.dist.worker.schema;

import static com.baidu.bifromq.dist.worker.schema.KVSchemaUtil.buildMatchRoute;
import static com.baidu.bifromq.dist.worker.schema.KVSchemaUtil.toGroupRouteKey;
import static com.baidu.bifromq.dist.worker.schema.KVSchemaUtil.toNormalRouteKey;
import static com.baidu.bifromq.dist.worker.schema.KVSchemaUtil.toReceiverUrl;
import static com.baidu.bifromq.util.TopicUtil.escape;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.baidu.bifromq.dist.rpc.proto.MatchRoute;
import com.baidu.bifromq.dist.rpc.proto.RouteGroup;
import com.baidu.bifromq.type.MatchInfo;
import com.baidu.bifromq.util.BSUtil;
import com.google.protobuf.ByteString;
import org.testng.annotations.Test;

public class KVSchemaUtilTest {
    private static final int MqttBroker = 0;
    private static final int InboxService = 1;
    private static final int RuleEngine = 2;

    @Test
    public void testParseNormalMatchRecord() {
        String tenantId = "tenantId";
        String topicFilter = "/a/b/c";
        String receiverUrl = toReceiverUrl(MqttBroker, "inbox1", "delivererKey1");
        MatchRoute route = MatchRoute.newBuilder()
            .setTopicFilter(topicFilter)
            .setBrokerId(MqttBroker)
            .setReceiverId("inbox1")
            .setDelivererKey("delivererKey1")
            .setIncarnation(1L)
            .build();
        ByteString key = toNormalRouteKey(tenantId, topicFilter, toReceiverUrl(route));
        Matching matching = buildMatchRoute(key, BSUtil.toByteString(route.getIncarnation()));
        assertEquals(matching.tenantId, tenantId);
        assertEquals(matching.escapedTopicFilter, escape(topicFilter));
        assertEquals(matching.originalTopicFilter(), topicFilter);
        assertTrue(matching instanceof NormalMatching);
        assertEquals(((NormalMatching) matching).receiverUrl, receiverUrl);

        MatchInfo matchInfo = ((NormalMatching) matching).matchInfo();
        assertEquals(matchInfo.getReceiverId(), route.getReceiverId());
        assertEquals(matchInfo.getTopicFilter(), topicFilter);
        assertEquals(matchInfo.getIncarnation(), route.getIncarnation());

        assertEquals(((NormalMatching) matching).subBrokerId(), MqttBroker);
        assertEquals(((NormalMatching) matching).delivererKey(), route.getDelivererKey());
    }

    @Test
    public void testParseGroupMatchRecord() {
        String origTopicFilter = "$share/group//a/b/c";
        MatchRoute route = MatchRoute.newBuilder()
            .setTopicFilter(origTopicFilter)
            .setBrokerId(MqttBroker)
            .setReceiverId("inbox1")
            .setDelivererKey("server1")
            .setIncarnation(1L)
            .build();

        String scopedReceiverId = toReceiverUrl(MqttBroker, "inbox1", "server1");
        ByteString key = toGroupRouteKey("tenantId", origTopicFilter);
        RouteGroup groupMembers = RouteGroup.newBuilder()
            .putMembers(scopedReceiverId, route.getIncarnation())
            .build();
        Matching matching = buildMatchRoute(key, groupMembers.toByteString());
        assertEquals(matching.tenantId, "tenantId");
        assertEquals(matching.escapedTopicFilter, escape("/a/b/c"));
        assertEquals(matching.originalTopicFilter(), origTopicFilter);
        assertTrue(matching instanceof GroupMatching);
        assertEquals(((GroupMatching) matching).receivers.get(scopedReceiverId), 1);
        assertEquals(((GroupMatching) matching).receiverList.get(0).receiverUrl, scopedReceiverId);
        assertEquals(((GroupMatching) matching).receiverList.get(0).incarnation(), route.getIncarnation());

        MatchInfo matchInfo = ((GroupMatching) matching).receiverList.get(0).matchInfo();
        assertEquals(matchInfo.getReceiverId(), "inbox1");
        assertEquals(((GroupMatching) matching).receiverList.get(0).subBrokerId(), MqttBroker);
        assertEquals(((GroupMatching) matching).receiverList.get(0).delivererKey(), "server1");
    }
}
