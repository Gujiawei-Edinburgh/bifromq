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

package com.baidu.bifromq.basekv.balance.impl;

import static com.baidu.bifromq.basekv.proto.State.StateType.Normal;

import com.baidu.bifromq.basehlc.HLC;
import com.baidu.bifromq.basekv.balance.StoreBalancer;
import com.baidu.bifromq.basekv.balance.command.ChangeConfigCommand;
import com.baidu.bifromq.basekv.proto.KVRangeDescriptor;
import com.baidu.bifromq.basekv.proto.KVRangeId;
import com.baidu.bifromq.basekv.proto.KVRangeStoreDescriptor;
import com.baidu.bifromq.basekv.raft.proto.ClusterConfig;
import com.baidu.bifromq.basekv.raft.proto.RaftNodeStatus;
import com.baidu.bifromq.basekv.raft.proto.RaftNodeSyncState;
import com.baidu.bifromq.basekv.utils.KVRangeIdUtil;
import com.google.common.collect.Sets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The UnreachableReplicaRemovalBalancer is a specialized balancer responsible for managing and removing unreachable
 * replicas from a distributed key-value store. An unreachable replica is defined as a replica that has been in a
 * "probing" state for a specified duration and is no longer present in its hosting store.
 */
public final class UnreachableReplicaRemovalBalancer extends StoreBalancer {
    private final Supplier<Long> millisSource;
    private final Duration suspicionDuration;
    // A map to track when a replica was last considered unhealthy
    // key: id of the leader KVRange in local store
    // value: a map to track when a replica was last observed in probing status
    private final Map<KVRangeId, Map<String, Long>> replicaSuspicionTimeMap = new ConcurrentHashMap<>();
    private volatile Map<String, Map<KVRangeId, KVRangeDescriptor>> latestDescriptorMap = new HashMap<>();

    /**
     * Constructor of the balancer with 15 seconds of suspicion duration.
     *
     * @param clusterId    the id of the BaseKV cluster which the store belongs to
     * @param localStoreId the id of the store which the balancer is responsible for
     */
    public UnreachableReplicaRemovalBalancer(String clusterId, String localStoreId) {
        this(clusterId, localStoreId, Duration.ofSeconds(15), HLC.INST::getPhysical);
    }

    /**
     * Constructor of the balancer with 15 seconds of suspicion duration.
     *
     * @param clusterId         the id of the BaseKV cluster which the store belongs to
     * @param localStoreId      the id of the store which the balancer is responsible for
     * @param suspicionDuration the duration of the replica being suspected unreachable
     */
    public UnreachableReplicaRemovalBalancer(String clusterId, String localStoreId, Duration suspicionDuration) {
        this(clusterId, localStoreId, suspicionDuration, HLC.INST::getPhysical);
    }

    /**
     * Constructor of balancer.
     *
     * @param clusterId         the id of the BaseKV cluster which the store belongs to
     * @param localStoreId      the id of the store which the balancer is responsible for
     * @param suspicionDuration the duration of the replica being suspected unreachable
     * @param millisSource      the time source in milliseconds precision
     */
    UnreachableReplicaRemovalBalancer(String clusterId,
                                      String localStoreId,
                                      Duration suspicionDuration,
                                      Supplier<Long> millisSource) {
        super(clusterId, localStoreId);
        this.millisSource = millisSource;
        this.suspicionDuration = suspicionDuration;
    }

    @Override
    public void update(String loadRules, Set<KVRangeStoreDescriptor> storeDescriptors) {
        Map<String, Map<KVRangeId, KVRangeDescriptor>> descriptorMap = build(storeDescriptors);
        latestDescriptorMap = descriptorMap;

        // Track the current leaders
        Set<KVRangeId> currentLeaders = new HashSet<>();

        for (Map.Entry<KVRangeId, KVRangeDescriptor> rangeEntry : descriptorMap.get(localStoreId).entrySet()) {
            KVRangeId rangeId = rangeEntry.getKey();
            KVRangeDescriptor rangeDescriptor = rangeEntry.getValue();
            if (rangeDescriptor.getRole() == RaftNodeStatus.Leader) {
                currentLeaders.add(rangeId);

                Map<String, Long> probingReplicas =
                    replicaSuspicionTimeMap.computeIfAbsent(rangeId, k -> new ConcurrentHashMap<>());
                Set<String> currentReplicas = rangeDescriptor.getSyncStateMap().keySet();

                // Remove replicas from the map if they no longer exist in the current SyncState
                probingReplicas.keySet().removeIf(replicaId -> !currentReplicas.contains(replicaId));

                // Update or add replicas that are in probing status
                for (Map.Entry<String, RaftNodeSyncState> entry : rangeDescriptor.getSyncStateMap().entrySet()) {
                    String replicaId = entry.getKey();
                    RaftNodeSyncState syncState = entry.getValue();

                    if (syncState.equals(RaftNodeSyncState.Probing)) {
                        probingReplicas.putIfAbsent(replicaId, millisSource.get());
                    } else {
                        probingReplicas.remove(replicaId);
                    }
                }
            }
        }

        // Remove entries from replicaSuspicionTimeMap if they are no longer leaders
        replicaSuspicionTimeMap.keySet().removeIf(kvRangeId -> !currentLeaders.contains(kvRangeId));
    }

    @Override
    public Optional<ChangeConfigCommand> balance() {
        long currentTime = millisSource.get();
        Map<String, Map<KVRangeId, KVRangeDescriptor>> storeDescriptors = latestDescriptorMap;
        for (KVRangeId rangeId : replicaSuspicionTimeMap.keySet()) {
            Map<String, Long> probingReplicas = replicaSuspicionTimeMap.get(rangeId);
            if (probingReplicas == null || probingReplicas.isEmpty()) {
                continue;
            }

            for (Map.Entry<String, Long> entry : probingReplicas.entrySet()) {
                String replicaId = entry.getKey();
                long suspicionTime = entry.getValue();
                Set<String> unhealthyReplicas = new HashSet<>();
                if (Duration.ofMillis(currentTime - suspicionTime).compareTo(suspicionDuration) > 0
                    && isMissingInStore(rangeId, replicaId, storeDescriptors)) {
                    unhealthyReplicas.add(replicaId);
                }
                KVRangeDescriptor rangeDescriptor = storeDescriptors.get(localStoreId).get(rangeId);
                if (!unhealthyReplicas.isEmpty() && rangeDescriptor.getState() == Normal) {
                    ClusterConfig clusterConfig = rangeDescriptor.getConfig();
                    log.debug("Remove unhealthy replicas: rangeId={}, replicas={}",
                        KVRangeIdUtil.toString(rangeId), unhealthyReplicas);
                    return Optional.of(ChangeConfigCommand.builder()
                        .toStore(localStoreId)
                        .kvRangeId(rangeId)
                        .expectedVer(rangeDescriptor.getVer())
                        .voters(Sets.difference(Sets.newHashSet(clusterConfig.getVotersList()), unhealthyReplicas))
                        .learners(Sets.difference(Sets.newHashSet(clusterConfig.getLearnersList()), unhealthyReplicas))
                        .build());
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, Map<KVRangeId, KVRangeDescriptor>> build(Set<KVRangeStoreDescriptor> descriptors) {
        Map<String, Map<KVRangeId, KVRangeDescriptor>> descriptorMap = new HashMap<>();
        for (KVRangeStoreDescriptor storeDescriptor : descriptors) {
            Map<KVRangeId, KVRangeDescriptor> rangeDescriptorMap = new HashMap<>();
            for (KVRangeDescriptor rangeDescriptor : storeDescriptor.getRangesList()) {
                rangeDescriptorMap.put(rangeDescriptor.getId(), rangeDescriptor);
            }
            descriptorMap.put(storeDescriptor.getId(), rangeDescriptorMap);
        }
        return descriptorMap;
    }

    private boolean isMissingInStore(KVRangeId rangeId, String storeId,
                                     Map<String, Map<KVRangeId, KVRangeDescriptor>> descriptorMap) {
        return descriptorMap.containsKey(storeId) && !descriptorMap.get(storeId).containsKey(rangeId);
    }
}
