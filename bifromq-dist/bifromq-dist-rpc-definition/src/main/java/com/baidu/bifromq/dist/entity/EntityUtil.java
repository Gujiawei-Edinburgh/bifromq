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

import static com.baidu.bifromq.util.TopicConst.DELIMITER_CHAR;
import static com.baidu.bifromq.util.TopicConst.NUL;
import static com.baidu.bifromq.util.TopicConst.NUL_CHAR;
import static com.baidu.bifromq.util.TopicConst.ORDERED_SHARE;
import static com.baidu.bifromq.util.TopicConst.UNORDERED_SHARE;
import static com.baidu.bifromq.util.TopicUtil.escape;
import static com.baidu.bifromq.util.TopicUtil.isNormalTopicFilter;
import static com.baidu.bifromq.util.TopicUtil.unescape;
import static com.google.protobuf.ByteString.copyFromUtf8;

import com.baidu.bifromq.dist.rpc.proto.GroupMatchRecord;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class EntityUtil {
    private static final ByteString INFIX_MATCH_RECORD_INFIX = copyFromUtf8("1");
    private static final ByteString INFIX_UPPERBOUND_INFIX = copyFromUtf8("2");
    private static final char FLAG_NORMAL_VAL = 1;
    private static final char FLAG_UNORDERED_VAL = 2;
    private static final char FLAG_ORDERED_VAL = 3;
    private static final ByteString FLAG_NORMAL = copyFromUtf8(String.valueOf(FLAG_NORMAL_VAL));
    private static final ByteString FLAG_UNORDERD_SHARE = copyFromUtf8(String.valueOf(FLAG_UNORDERED_VAL));
    private static final ByteString FLAG_ORDERD_SHARE = copyFromUtf8(String.valueOf(FLAG_ORDERED_VAL));

    public static String toQInboxId(int subBrokerId, String inboxId, String delivererKey) {
        String scoped = subBrokerId + NUL + inboxId + NUL + delivererKey;
        return Base64.getEncoder().encodeToString(scoped.getBytes(StandardCharsets.UTF_8));
    }

    public static String toScopedQInboxId(String tenantId, String qInboxId) {
        return tenantId + NUL + qInboxId;
    }

    public static String toScopedTopicFilter(String tenantId, String qInboxId, String topicFilter) {
        return tenantId + NUL + qInboxId + NUL + topicFilter;
    }

    public static String parseTenantIdFromScopedTopicFilter(String scopedTopicFilter) {
        return scopedTopicFilter.substring(0, scopedTopicFilter.indexOf(NUL_CHAR));
    }

    public static String parseQInboxIdFromScopedTopicFilter(String scopedTopicFilter) {
        return scopedTopicFilter.substring(scopedTopicFilter.indexOf(NUL_CHAR) + 1,
            scopedTopicFilter.lastIndexOf(NUL));
    }

    public static String parseTopicFilterFromScopedTopicFilter(String scopedTopicFilter) {
        return scopedTopicFilter.substring(scopedTopicFilter.lastIndexOf(NUL_CHAR) + 1);
    }

    public static int parseSubBroker(String scopedInboxId) {
        scopedInboxId = new String(Base64.getDecoder().decode(scopedInboxId), StandardCharsets.UTF_8);
        int splitIdx = scopedInboxId.indexOf(NUL_CHAR);
        return Integer.parseInt(scopedInboxId.substring(0, splitIdx));
    }

    public static ByteString tenantPrefix(String tenantId) {
        return copyFromUtf8(tenantId + NUL);
    }

    public static ByteString tenantUpperBound(String tenantId) {
        return tenantPrefix(tenantId).concat(INFIX_UPPERBOUND_INFIX);
    }

    public static String parseTenantId(ByteString rawKey) {
        String keyStr = rawKey.toStringUtf8();
        int firstSplit = keyStr.indexOf(NUL_CHAR);
        return keyStr.substring(0, firstSplit);
    }

    public static String parseTenantId(String rawKeyUtf8) {
        int firstSplit = rawKeyUtf8.indexOf(NUL_CHAR);
        return rawKeyUtf8.substring(0, firstSplit);
    }

    public static Matching.Type getType(ByteString matchRecordKey) {
        String matchRecordKeyStr = matchRecordKey.toStringUtf8();
        int lastSplit = matchRecordKeyStr.lastIndexOf(NUL_CHAR);
        char flag = matchRecordKeyStr.charAt(lastSplit + 1);
        return switch (flag) {
            case '0' -> Matching.Type.Normal;
            default -> Matching.Type.Group;
        };
    }

    public static Matching parseMatchRecord(ByteString matchRecordKey, ByteString matchRecordValue) {
        // <tenantId><NUL><1><ESCAPED_TOPIC_FILTER><NUL><FLAG><SCOPED_INBOX|SHARE_GROUP>
        String matchRecordKeyStr = matchRecordKey.toStringUtf8();
        int lastSplit = matchRecordKeyStr.lastIndexOf(NUL_CHAR);
        char flag = matchRecordKeyStr.charAt(lastSplit + 1);
        try {
            switch (flag) {
                case FLAG_NORMAL_VAL:
                    String scopedInbox = matchRecordKeyStr.substring(lastSplit + 2);
                    return new NormalMatching(matchRecordKey, scopedInbox, toLong(matchRecordValue));
                case FLAG_UNORDERED_VAL:
                case FLAG_ORDERED_VAL:
                default:
                    GroupMatchRecord matchRecord = GroupMatchRecord.parseFrom(matchRecordValue);
                    String group = matchRecordKeyStr.substring(lastSplit + 2);
                    return new GroupMatching(matchRecordKey, group, flag == FLAG_ORDERED_VAL,
                        matchRecord.getQReceiverIdMap());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse matching record", e);
        }
    }

    public static ByteString matchRecordKeyPrefix(String tenantId) {
        return tenantPrefix(tenantId).concat(INFIX_MATCH_RECORD_INFIX);
    }

    public static ByteString matchRecordKeyPrefix(String tenantId, String topicFilter) {
        return matchRecordKeyPrefix(tenantId).concat(copyFromUtf8(escape(topicFilter) + NUL));
    }

    public static ByteString matchRecordPrefixWithEscapedTopicFilter(String tenantId, String escapedTopicFilter) {
        return matchRecordKeyPrefix(tenantId).concat(copyFromUtf8(escapedTopicFilter + NUL));
    }

    public static ByteString toNormalMatchRecordKey(String tenantId, String topicFilter, String qInboxId) {
        assert isNormalTopicFilter(topicFilter);
        return matchRecordKeyPrefix(tenantId, topicFilter)
            .concat(FLAG_NORMAL)
            .concat(copyFromUtf8(qInboxId));
    }

    public static ByteString toGroupMatchRecordKey(String tenantId, String topicFilter) {
        assert !isNormalTopicFilter(topicFilter);
        SharedTopicFilter stf = parseSharedTopic(topicFilter);
        return matchRecordKeyPrefix(tenantId, stf.topicFilter)
            .concat(stf.ordered ? FLAG_ORDERD_SHARE : FLAG_UNORDERD_SHARE)
            .concat(copyFromUtf8(stf.shareGroup));
    }

    public static ByteString toMatchRecordKey(String tenantId, String topicFilter, String qInboxId) {
        if (isNormalTopicFilter(topicFilter)) {
            return matchRecordKeyPrefix(tenantId, topicFilter)
                .concat(FLAG_NORMAL)
                .concat(copyFromUtf8(qInboxId));
        } else {
            SharedTopicFilter stf = parseSharedTopic(topicFilter);
            return matchRecordKeyPrefix(tenantId, stf.topicFilter)
                .concat(stf.ordered ? FLAG_ORDERD_SHARE : FLAG_UNORDERD_SHARE)
                .concat(copyFromUtf8(stf.shareGroup));
        }
    }

    public static int matchRecordSize(String tenantId, String topicFilter, String qInboxId) {
        return toMatchRecordKey(tenantId, topicFilter, qInboxId).size() + 1;
    }

    public static ByteString toMatchRecordKeyPrefix(String tenantId, String topicFilter) {
        if (isNormalTopicFilter(topicFilter)) {
            return matchRecordKeyPrefix(tenantId, topicFilter);
        } else {
            SharedTopicFilter stf = parseSharedTopic(topicFilter);
            return matchRecordKeyPrefix(tenantId, stf.topicFilter);
        }
    }

    private static SharedTopicFilter parseSharedTopic(String topicFilter) {
        assert !isNormalTopicFilter(topicFilter);
        String sharePrefix = topicFilter.startsWith(UNORDERED_SHARE) ? UNORDERED_SHARE : ORDERED_SHARE;
        boolean ordered = !topicFilter.startsWith(UNORDERED_SHARE);
        String rest = topicFilter.substring((sharePrefix + DELIMITER_CHAR).length());
        int firstTopicSeparatorIndex = rest.indexOf(DELIMITER_CHAR);
        String shareGroup = rest.substring(0, firstTopicSeparatorIndex);
        return new SharedTopicFilter(topicFilter, ordered, shareGroup,
            rest.substring(firstTopicSeparatorIndex + 1));
    }

    public static String parseTopicFilter(String matchRecordKeyStr) {
        // <tenantId><NUL><1><ESCAPED_TOPIC_FILTER><NUL><FLAG><SCOPED_INBOX|SHARE_GROUP>
        int firstSplit = matchRecordKeyStr.indexOf(NUL_CHAR);
        int lastSplit = matchRecordKeyStr.lastIndexOf(NUL_CHAR);
        return unescape(matchRecordKeyStr.substring(firstSplit + 2, lastSplit));
    }

    public static String parseOriginalTopicFilter(String matchRecordKeyStr) {
        // <tenantId><NUL><1><ESCAPED_TOPIC_FILTER><NUL><FLAG><SCOPED_INBOX|SHARE_GROUP>
        int firstSplit = matchRecordKeyStr.indexOf(NUL_CHAR);
        int lastSplit = matchRecordKeyStr.lastIndexOf(NUL_CHAR);
        String topicFilter = unescape(matchRecordKeyStr.substring(firstSplit + 2, lastSplit));
        char flag = matchRecordKeyStr.charAt(lastSplit + 1);
        switch (flag) {
            case FLAG_NORMAL_VAL -> {
                return topicFilter;
            }
            case FLAG_UNORDERED_VAL -> {
                String group = matchRecordKeyStr.substring(lastSplit + 2);
                return UNORDERED_SHARE + DELIMITER_CHAR + group + DELIMITER_CHAR + topicFilter;
            }
            case FLAG_ORDERED_VAL -> {
                String group = matchRecordKeyStr.substring(lastSplit + 2);
                return ORDERED_SHARE + DELIMITER_CHAR + group + DELIMITER_CHAR + topicFilter;
            }
            default -> throw new UnsupportedOperationException("Unknown flag: " + flag);
        }
    }

    public static TenantAndEscapedTopicFilter parseTenantAndEscapedTopicFilter(ByteString matchRecordKey) {
        String matchRecordKeyStr = matchRecordKey.toStringUtf8();
        int firstSplit = matchRecordKeyStr.indexOf(NUL_CHAR);
        int lastSplit = matchRecordKeyStr.lastIndexOf(NUL_CHAR);
        return new TenantAndEscapedTopicFilter(matchRecordKeyStr.substring(0, firstSplit),
            matchRecordKeyStr.substring(firstSplit + 2, lastSplit));
    }

    public record TenantAndEscapedTopicFilter(String tenantId, String escapedTopicFilter) {
        public String toGlobalTopicFilter() {
            return tenantId + NUL + escapedTopicFilter;
        }
    }

    public static long toLong(ByteString b) {
        assert b.size() == Long.BYTES;
        ByteBuffer buffer = b.asReadOnlyByteBuffer();
        return buffer.getLong();
    }

    public static ByteString toByteString(long l) {
        return UnsafeByteOperations.unsafeWrap(ByteBuffer.allocate(Long.BYTES).putLong(l).array());
    }
}
