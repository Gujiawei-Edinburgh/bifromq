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

package com.baidu.bifromq.basekv;

import static com.baidu.bifromq.basekv.proto.ProposalResult.ACCEPTED;
import static com.baidu.bifromq.basekv.proto.ProposalResult.REJECTED;

import com.baidu.bifromq.basecrdt.core.api.CausalCRDTType;
import com.baidu.bifromq.basecrdt.core.api.IMVReg;
import com.baidu.bifromq.basecrdt.core.api.IORMap;
import com.baidu.bifromq.basecrdt.core.api.MVRegOperation;
import com.baidu.bifromq.basecrdt.core.api.ORMapOperation;
import com.baidu.bifromq.basecrdt.proto.Replica;
import com.baidu.bifromq.basehlc.HLC;
import com.baidu.bifromq.basekv.proto.DescriptorKey;
import com.baidu.bifromq.basekv.proto.KVRangeStoreDescriptor;
import com.baidu.bifromq.basekv.proto.LoadRules;
import com.baidu.bifromq.basekv.proto.LoadRulesProposition;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.reactivex.rxjava3.subjects.Subject;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class BaseKVClusterMetadataManager implements IBaseKVClusterMetadataManager {

    private record StoreDescriptorAndReplicas(Map<DescriptorKey, KVRangeStoreDescriptor> descriptorMap,
                                              Set<ByteString> replicaIds) {
    }

    private static final ByteString ORMapKey_LoadRules_Proposal = ByteString.copyFromUtf8("LoadRulesProposal");
    private static final ByteString ORMapKey_LoadRules = ByteString.copyFromUtf8("LoadRules");
    private static final ByteString ORMapKey_Landscape = ByteString.copyFromUtf8("Landscape");
    private final IORMap loadRulesORMap;
    private final IORMap loadRulesProposalORMap;
    private final IORMap landscapeORMap;
    private final Duration proposalTimeout;
    private final Subject<Map<String, Struct>> loadRulesSubject = BehaviorSubject.create();
    private final BehaviorSubject<Map<DescriptorKey, KVRangeStoreDescriptor>> landscapeSubject =
        BehaviorSubject.create();
    private final CompositeDisposable disposable = new CompositeDisposable();
    private volatile LoadRulesProposalHandler handler = null;

    public BaseKVClusterMetadataManager(IORMap basekvClusterDescriptor,
                                        Observable<Set<Replica>> aliveReplicas,
                                        Duration proposalTimeout) {
        this.loadRulesORMap = basekvClusterDescriptor.getORMap(ORMapKey_LoadRules);
        this.loadRulesProposalORMap = basekvClusterDescriptor.getORMap(ORMapKey_LoadRules_Proposal);
        this.landscapeORMap = basekvClusterDescriptor.getORMap(ORMapKey_Landscape);
        this.proposalTimeout = proposalTimeout;
        disposable.add(landscapeORMap.inflation()
            .map(this::buildLandscape)
            .subscribe(landscapeSubject::onNext));
        disposable.add(loadRulesORMap.inflation()
            .map(this::buildBalancerLoadRules)
            .subscribe(loadRulesSubject::onNext));
        disposable.add(loadRulesProposalORMap.inflation()
            .map(this::buildLoadRulesProposition)
            .map(m -> {
                Map<String, LoadRulesProposition> result = new HashMap<>();
                m.forEach((k, v) -> {
                    if (v.hasProposal()) {
                        result.put(k, v);
                    }
                });
                return result;
            })
            .scan((prev, curr) -> {
                if (prev == null) {
                    return curr;
                } else {
                    Map<String, LoadRulesProposition> result = new HashMap<>();
                    curr.forEach((k, v) -> {
                        if (!prev.containsKey(k) || !prev.get(k).equals(v)) {
                            result.put(k, v);
                        }
                    });
                    return result;
                }
            })
            .filter(m -> !m.isEmpty())
            .subscribe(this::handleProposals));
        disposable.add(Observable.combineLatest(landscapeSubject, aliveReplicas
                    .map(replicas -> replicas.stream().map(Replica::getId).collect(Collectors.toSet())),
                (StoreDescriptorAndReplicas::new))
            .subscribe(this::checkAndHealStoreDescriptorList));
    }

    @Override
    public Observable<Map<String, Struct>> loadRules() {
        return loadRulesSubject.distinctUntilChanged();
    }

    @Override
    public void setLoadRulesProposalHandler(LoadRulesProposalHandler handler) {
        this.handler = handler;
    }

    @Override
    public CompletableFuture<ProposalResult> proposeLoadRules(String balancerClassFQN, Struct loadRules) {
        IMVReg balancerProposalMVReg = loadRulesProposalORMap.getMVReg(ByteString.copyFromUtf8(balancerClassFQN));
        CompletableFuture<ProposalResult> resultFuture = new CompletableFuture<>();
        long now = HLC.INST.get();
        balancerProposalMVReg.inflation()
            .timeout(proposalTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .mapOptional(ts -> {
                Optional<LoadRulesProposition> propositionOpt = buildLoadRulesProposition(balancerProposalMVReg);
                if (propositionOpt.isEmpty()) {
                    return Optional.of(ProposalResult.NO_BALANCER);
                }
                LoadRulesProposition loadRulesProposition = propositionOpt.get();
                if (loadRulesProposition.getHlc() == now) {
                    if (loadRulesProposition.hasProposalResult()) {
                        switch (loadRulesProposition.getProposalResult()) {
                            case ACCEPTED:
                                return Optional.of(ProposalResult.ACCEPTED);
                            default:
                                return Optional.of(ProposalResult.REJECTED);
                        }
                    }
                    return Optional.empty();
                } else if (loadRulesProposition.getHlc() > now) {
                    return Optional.of(ProposalResult.OVERRIDDEN);
                } else {
                    return Optional.empty();
                }
            })
            .take(1)
            .subscribe(resultFuture::complete, resultFuture::completeExceptionally);
        balancerProposalMVReg.execute(MVRegOperation.write(LoadRulesProposition.newBuilder()
            .setProposal(loadRules)
            .setHlc(now)
            .build().toByteString()));
        return resultFuture;
    }

    @Override
    public Observable<Map<String, KVRangeStoreDescriptor>> landscape() {
        return landscapeSubject
            .map(descriptorMap -> {
                Map<String, KVRangeStoreDescriptor> descriptorMapByStoreId = new HashMap<>();
                descriptorMap.forEach((key, value) -> descriptorMapByStoreId.compute(key.getStoreId(), (k, v) -> {
                    if (v == null) {
                        return value;
                    }
                    return v.getHlc() > value.getHlc() ? v : value;
                }));
                return descriptorMapByStoreId;
            });
    }

    @Override
    public Optional<KVRangeStoreDescriptor> getStoreDescriptor(String storeId) {
        return Optional.ofNullable(landscapeSubject.getValue().get(toDescriptorKey(storeId)));
    }

    @Override
    public CompletableFuture<Void> report(KVRangeStoreDescriptor descriptor) {
        DescriptorKey descriptorKey = toDescriptorKey(descriptor.getId());
        Optional<KVRangeStoreDescriptor> descriptorOnCRDT =
            buildLandscape(landscapeORMap.getMVReg(descriptorKey.toByteString()));
        if (descriptorOnCRDT.isEmpty() || !descriptorOnCRDT.get().equals(descriptor)) {
            return landscapeORMap.execute(ORMapOperation
                .update(descriptorKey.toByteString())
                .with(MVRegOperation.write(descriptor.toByteString())));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stopReport(String storeId) {
        return removeDescriptorFromCRDT(toDescriptorKey(storeId));
    }

    public void stop() {
        disposable.dispose();
        loadRulesSubject.onComplete();
        landscapeSubject.onComplete();
    }

    private void handleProposals(Map<String, LoadRulesProposition> proposals) {
        LoadRulesProposalHandler handler = this.handler;
        if (handler != null) {
            for (String balancerClassFQN : proposals.keySet()) {
                LoadRulesProposition proposition = proposals.get(balancerClassFQN);
                Struct loadRules = proposition.getProposal();
                switch (handler.handle(balancerClassFQN, loadRules)) {
                    case ACCEPTED -> {
                        loadRulesORMap.execute(ORMapOperation
                            .update(ByteString.copyFromUtf8(balancerClassFQN))
                            .with(MVRegOperation.write(LoadRules.newBuilder()
                                .setLoadRules(loadRules)
                                .setHlc(proposition.getHlc()) // keep same HLC
                                .build().toByteString())));
                        loadRulesProposalORMap.execute(ORMapOperation
                            .update(ByteString.copyFromUtf8(balancerClassFQN))
                            .with(MVRegOperation.write(LoadRulesProposition.newBuilder()
                                .setProposalResult(ACCEPTED)
                                .setHlc(proposition.getHlc()) // keep same HLC
                                .build().toByteString())));
                    }
                    case REJECTED -> loadRulesProposalORMap.execute(ORMapOperation
                        .update(ByteString.copyFromUtf8(balancerClassFQN))
                        .with(MVRegOperation.write(LoadRulesProposition.newBuilder()
                            .setProposalResult(REJECTED)
                            .setHlc(proposition.getHlc()) // keep same HLC
                            .build().toByteString())));
                    case NO_BALANCER -> loadRulesProposalORMap.execute(ORMapOperation
                        .remove(ByteString.copyFromUtf8(balancerClassFQN))
                        .of(CausalCRDTType.mvreg));
                }
            }
        }
    }

    private Map<String, Struct> buildBalancerLoadRules(long ts) {
        Map<String, Struct> loadRulesMap = new HashMap<>();
        loadRulesORMap.keys().forEachRemaining(ormapKey -> {
            String balancerClassFQN = ormapKey.key().toStringUtf8();
            Optional<LoadRules> loadRules = buildLoadRules(loadRulesORMap.getMVReg(ormapKey.key()));
            loadRules.ifPresent(rules -> loadRulesMap.put(balancerClassFQN, rules.getLoadRules()));
        });
        return loadRulesMap;
    }

    private Optional<LoadRules> buildLoadRules(IMVReg mvReg) {
        List<LoadRules> l = Lists.newArrayList(Iterators.filter(Iterators.transform(mvReg.read(), b -> {
            try {
                return LoadRules.parseFrom(b);
            } catch (InvalidProtocolBufferException e) {
                log.error("Unable to parse LoadRules", e);
                return null;
            }
        }), Objects::nonNull));
        l.sort((a, b) -> Long.compareUnsigned(b.getHlc(), a.getHlc()));
        return Optional.ofNullable(l.isEmpty() ? null : l.get(0));
    }

    private Map<String, LoadRulesProposition> buildLoadRulesProposition(long ts) {
        Map<String, LoadRulesProposition> loadRulesPropositionMap = new HashMap<>();
        loadRulesProposalORMap.keys().forEachRemaining(ormapKey ->
            buildLoadRulesProposition(loadRulesProposalORMap.getMVReg(ormapKey.key()))
                .ifPresent(proposition -> loadRulesPropositionMap.put(ormapKey.key().toStringUtf8(), proposition)));
        return loadRulesPropositionMap;
    }

    private Optional<LoadRulesProposition> buildLoadRulesProposition(IMVReg mvReg) {
        List<LoadRulesProposition> l = Lists.newArrayList(Iterators.filter(Iterators.transform(mvReg.read(), b -> {
            try {
                return LoadRulesProposition.parseFrom(b);
            } catch (InvalidProtocolBufferException e) {
                log.error("Unable to parse LoadRulesProposal", e);
                return null;
            }
        }), Objects::nonNull));
        l.sort((a, b) -> Long.compareUnsigned(b.getHlc(), a.getHlc()));
        return Optional.ofNullable(l.isEmpty() ? null : l.get(0));
    }

    private Map<DescriptorKey, KVRangeStoreDescriptor> buildLandscape(long ts) {
        Map<DescriptorKey, KVRangeStoreDescriptor> landscape = new HashMap<>();
        landscapeORMap.keys().forEachRemaining(ormapKey ->
            buildLandscape(landscapeORMap.getMVReg(ormapKey.key()))
                .ifPresent(descriptor -> landscape.put(parseDescriptorKey(ormapKey.key()), descriptor)));
        return landscape;
    }

    private Optional<KVRangeStoreDescriptor> buildLandscape(IMVReg mvReg) {
        List<KVRangeStoreDescriptor> l = Lists.newArrayList(Iterators.filter(Iterators.transform(mvReg.read(), b -> {
            try {
                return KVRangeStoreDescriptor.parseFrom(b);
            } catch (InvalidProtocolBufferException e) {
                log.error("Unable to parse KVRangeStoreDescriptor", e);
                return null;
            }
        }), Objects::nonNull));
        l.sort((a, b) -> Long.compareUnsigned(b.getHlc(), a.getHlc()));
        return Optional.ofNullable(l.isEmpty() ? null : l.get(0));
    }

    private void checkAndHealStoreDescriptorList(StoreDescriptorAndReplicas storeDescriptorAndReplicas) {
        Map<DescriptorKey, KVRangeStoreDescriptor> storedDescriptors = storeDescriptorAndReplicas.descriptorMap;
        Set<ByteString> aliveReplicas = storeDescriptorAndReplicas.replicaIds;
        for (DescriptorKey descriptorKey : storedDescriptors.keySet()) {
            if (!aliveReplicas.contains(descriptorKey.getReplicaId())) {
                log.debug("store[{}] is not alive, remove its storeDescriptor", descriptorKey.getStoreId());
                removeDescriptorFromCRDT(descriptorKey);
            }
        }
    }

    private CompletableFuture<Void> removeDescriptorFromCRDT(DescriptorKey key) {
        return landscapeORMap.execute(ORMapOperation.remove(key.toByteString()).of(CausalCRDTType.mvreg));
    }

    private DescriptorKey toDescriptorKey(String storeId) {
        return DescriptorKey.newBuilder()
            .setStoreId(storeId)
            .setReplicaId(landscapeORMap.id().getId())
            .build();
    }

    private DescriptorKey parseDescriptorKey(ByteString key) {
        try {
            return DescriptorKey.parseFrom(key);
        } catch (InvalidProtocolBufferException e) {
            log.error("Unable to parse DescriptorKey", e);
            return null;
        }
    }
}