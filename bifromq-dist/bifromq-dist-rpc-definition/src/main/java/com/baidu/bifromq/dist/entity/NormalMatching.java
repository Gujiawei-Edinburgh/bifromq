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

package com.baidu.bifromq.dist.entity;


import static com.baidu.bifromq.util.TopicConst.NUL;
import static com.baidu.bifromq.util.TopicUtil.unescape;

import com.baidu.bifromq.type.MatchInfo;
import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@EqualsAndHashCode(callSuper = true)
@ToString
public class NormalMatching extends Matching {
    public final String scopedInboxId;
    private final String originalTopicFilter;
    private final long incarnation;

    @EqualsAndHashCode.Exclude
    public final String delivererKey;
    @EqualsAndHashCode.Exclude
    public final int subBrokerId;
    @EqualsAndHashCode.Exclude
    public final MatchInfo matchInfo;

    NormalMatching(ByteString key, String scopedInboxId, long incarnation) {
        super(key);
        this.scopedInboxId = scopedInboxId;
        this.originalTopicFilter = unescape(escapedTopicFilter);
        this.incarnation = incarnation;

        scopedInboxId = new String(Base64.getDecoder().decode(scopedInboxId), StandardCharsets.UTF_8);
        String[] parts = scopedInboxId.split(NUL);
        subBrokerId = Integer.parseInt(parts[0]);
        delivererKey = Strings.isNullOrEmpty(parts[2]) ? null : parts[2];
        matchInfo = MatchInfo.newBuilder()
            .setReceiverId(parts[1])
            .setTopicFilter(originalTopicFilter)
            .setIncarnation(incarnation)
            .build();
    }

    NormalMatching(ByteString key, String originalTopicFilter, String scopedInboxId, long incarnation) {
        super(key);
        this.scopedInboxId = scopedInboxId;
        this.originalTopicFilter = originalTopicFilter;
        this.incarnation = incarnation;

        scopedInboxId = new String(Base64.getDecoder().decode(scopedInboxId), StandardCharsets.UTF_8);
        String[] parts = scopedInboxId.split(NUL);
        subBrokerId = Integer.parseInt(parts[0]);
        delivererKey = Strings.isNullOrEmpty(parts[2]) ? null : parts[2];
        matchInfo = MatchInfo.newBuilder()
            .setReceiverId(parts[1])
            .setTopicFilter(originalTopicFilter)
            .setIncarnation(incarnation)
            .build();
    }

    @Override
    public Type type() {
        return Type.Normal;
    }

    @Override
    public String originalTopicFilter() {
        return originalTopicFilter;
    }

    public long incarnation() {
        return incarnation;
    }
}
