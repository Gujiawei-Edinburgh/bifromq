/*
 * Copyright (c) 2025. The BifroMQ Authors. All Rights Reserved.
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

package com.baidu.bifromq.inbox.store.delay;

import com.baidu.bifromq.inbox.record.TenantInboxInstance;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class RetryableDelayedTask<R> implements IDelayedTask<TenantInboxInstance> {
    protected final Duration delay;
    protected final int retryCount;
    protected final long version;

    protected RetryableDelayedTask(Duration delay, long version, int retryCount) {
        this.delay = delay;
        this.version = version;
        this.retryCount = retryCount;
    }

    @Override
    public final CompletableFuture<Void> run(TenantInboxInstance key, IDelayTaskRunner<TenantInboxInstance> runner) {
        if (retryCount > 5) {
            log.debug("{} failed after {} retries, tenantId={}, inboxId={}, inc={}", getTaskName(), retryCount,
                key.tenantId(), key.instance().inboxId(), key.instance().incarnation());
            return CompletableFuture.completedFuture(null);
        }
        return callOperation(key, runner).thenAccept(response -> {
            if (shouldRetry(response)) {
                Duration retryDelay = Duration.ofMillis(ThreadLocalRandom.current().nextLong(100, 5000));
                runner.scheduleIfAbsent(key, () -> createRetryTask(retryDelay));
            }
        });
    }

    protected abstract CompletableFuture<R> callOperation(TenantInboxInstance key,
                                                          IDelayTaskRunner<TenantInboxInstance> runner);

    protected abstract boolean shouldRetry(R reply);

    protected abstract RetryableDelayedTask<R> createRetryTask(Duration newDelay);

    protected abstract String getTaskName();

    @Override
    public final Duration getDelay() {
        return delay;
    }
}
