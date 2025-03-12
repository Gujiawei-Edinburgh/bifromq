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

package com.baidu.bifromq.dist.worker.cache;

import com.baidu.bifromq.basekv.proto.Boundary;
import com.baidu.bifromq.dist.worker.schema.Matching;
import com.baidu.bifromq.type.RouteMatcher;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface ISubscriptionCache {
    CompletableFuture<Set<Matching>> get(String tenantId, String topic);

    boolean isCached(String tenantId, List<String> filterLevels);

    void refresh(Map<String, NavigableSet<RouteMatcher>> topicFiltersByTenant);

    void reset(Boundary boundary);

    void close();
}
