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

package com.baidu.bifromq.retain.store.index;

import static com.baidu.bifromq.util.TopicConst.MULTI_WILDCARD;
import static com.baidu.bifromq.util.TopicConst.SINGLE_WILDCARD;
import static com.baidu.bifromq.util.TopicConst.SYS_PREFIX;

import com.baidu.bifromq.util.TopicUtil;
import com.baidu.bifromq.util.index.Branch;
import com.baidu.bifromq.util.index.TopicLevelTrie;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RetainTopicIndex extends TopicLevelTrie<RetainedMsgInfo> implements IRetainTopicIndex {
    private static final BranchSelector BranchSelector = new BranchSelector() {
        @Override
        public <T> Map<Branch<T>, Action> selectBranch(Map<String, Branch<T>> branches,
                                                       List<String> topicLevels,
                                                       int currentLevel) {
            if (topicLevels.isEmpty()) {
                Map<Branch<T>, Action> result = new HashMap<>();
                for (Map.Entry<String, Branch<T>> entry : branches.entrySet()) {
                    Branch<T> branch = entry.getValue();
                    result.put(branch, Action.MATCH_AND_CONTINUE);
                }
                return result;
            }
            if (currentLevel < topicLevels.size() - 1) {
                // not last level
                boolean matchParent = currentLevel + 1 == topicLevels.size() - 1
                    && topicLevels.get(currentLevel + 1).equals(MULTI_WILDCARD);
                String topicLevelToMatch = topicLevels.get(currentLevel);
                switch (topicLevelToMatch) {
                    case SINGLE_WILDCARD -> {
                        Map<Branch<T>, Action> result = new HashMap<>();
                        for (Map.Entry<String, Branch<T>> entry : branches.entrySet()) {
                            Branch<T> branch = entry.getValue();
                            // the first level represents tenant
                            if (currentLevel == 1 && entry.getKey().startsWith(SYS_PREFIX)) {
                                // + skip SYS topic
                                continue;
                            }
                            result.put(branch, matchParent ? Action.MATCH_AND_CONTINUE : Action.CONTINUE);
                        }
                        return result;
                    }
                    default -> {
                        assert !topicLevelToMatch.equals(MULTI_WILDCARD) : "MULTI_WILDCARD should be the last level";
                        if (branches.containsKey(topicLevelToMatch)) {
                            return Map.of(branches.get(topicLevelToMatch),
                                matchParent ? Action.MATCH_AND_CONTINUE : Action.CONTINUE);
                        }
                        return Collections.emptyMap();
                    }
                }
            } else if (currentLevel == topicLevels.size() - 1) {
                // last level
                String topicLevelToMatch = topicLevels.get(currentLevel);
                switch (topicLevelToMatch) {
                    case SINGLE_WILDCARD -> {
                        Map<Branch<T>, Action> result = new HashMap<>();
                        for (Map.Entry<String, Branch<T>> entry : branches.entrySet()) {
                            Branch<T> branch = entry.getValue();
                            // the first level represents tenant
                            if (currentLevel == 1 && entry.getKey().startsWith(SYS_PREFIX)) {
                                // + skip SYS topic
                                continue;
                            }
                            result.put(branch, Action.MATCH_AND_STOP);
                        }
                        return result;
                    }
                    case MULTI_WILDCARD -> {
                        Map<Branch<T>, Action> result = new HashMap<>();
                        for (Map.Entry<String, Branch<T>> entry : branches.entrySet()) {
                            Branch<T> branch = entry.getValue();
                            // the first level represents tenant
                            if (currentLevel == 1 && entry.getKey().startsWith(SYS_PREFIX)) {
                                // # skip SYS topic
                                continue;
                            }
                            result.put(branch, Action.MATCH_AND_CONTINUE);
                        }
                        return result;
                    }
                    default -> {
                        if (branches.containsKey(topicLevelToMatch)) {
                            return Map.of(branches.get(topicLevelToMatch), Action.MATCH_AND_STOP);
                        }
                        return Collections.emptyMap();
                    }
                }
            } else {
                // # matches all descendant levels
                Map<Branch<T>, Action> result = new HashMap<>();
                for (Map.Entry<String, Branch<T>> entry : branches.entrySet()) {
                    Branch<T> branch = entry.getValue();
                    result.put(branch, Action.MATCH_AND_CONTINUE);
                }
                return result;
            }
        }
    };

    public RetainTopicIndex() {
        super(BranchSelector);
    }

    public void add(String tenantId, String topic, long timestamp, int expirySeconds) {
        add(TopicUtil.parse(tenantId, topic, false),
            new RetainedMsgInfo(tenantId, topic, timestamp, expirySeconds));
    }

    public void remove(String tenantId, String topic) {
        remove(TopicUtil.parse(tenantId, topic, false),
            new RetainedMsgInfo(tenantId, topic, 0, 0));
    }

    public List<RetainedMsgInfo> match(String tenantId, String topicFilter) {
        return lookup(TopicUtil.parse(tenantId, topicFilter, false));
    }

    @Override
    public List<RetainedMsgInfo> findAll() {
        return lookup(Collections.emptyList());
    }
}