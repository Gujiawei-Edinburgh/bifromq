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

package com.baidu.bifromq.sessiondict.server;

import com.baidu.bifromq.baserpc.server.AckStream;
import com.baidu.bifromq.sessiondict.rpc.proto.Quit;
import com.baidu.bifromq.sessiondict.rpc.proto.ServerRedirection;
import com.baidu.bifromq.sessiondict.rpc.proto.Session;
import com.baidu.bifromq.type.ClientInfo;
import com.google.common.collect.Sets;
import io.grpc.stub.StreamObserver;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class SessionRegister extends AckStream<Session, Quit> implements ISessionRegister {
    // keep the session registered via this stream
    private final Set<ClientInfo> sessionOwners = Sets.newConcurrentHashSet();
    private final IRegistrationListener regListener;
    private final Disposable disposable;

    SessionRegister(IRegistrationListener listener, StreamObserver<Quit> responseObserver) {
        super(responseObserver);
        this.regListener = listener;
        disposable = ack()
            .doFinally(() -> {
                log.debug("SessionRegister@{} closed: sessions={}", this.hashCode(), sessionOwners.size());
                sessionOwners.forEach(sessionOwner -> regListener.on(sessionOwner, false, this));
            })
            .subscribe(session -> {
                ClientInfo owner = session.getOwner();
                String tenantId = owner.getTenantId();
                assert this.tenantId.equals(tenantId);
                if (session.getKeep()) {
                    if (sessionOwners.add(owner)) {
                        listener.on(owner, true, this);
                    }
                } else {
                    if (sessionOwners.remove(owner)) {
                        listener.on(owner, false, this);
                    }
                }
            });
        log.debug("SessionRegister@{} created", this.hashCode());
    }

    @Override
    public void kick(String tenantId,
                     ClientInfo sessionOwner,
                     ClientInfo kicker,
                     ServerRedirection serverRedirection) {
        if (sessionOwners.remove(sessionOwner)) {
            send(Quit.newBuilder()
                .setReqId(System.nanoTime())
                .setOwner(sessionOwner)
                .setKiller(kicker)
                .setServerRedirection(serverRedirection).build());
            regListener.on(sessionOwner, false, this);
        }
    }

    @Override
    public void close() {
        super.close();
        disposable.dispose();
    }
}
