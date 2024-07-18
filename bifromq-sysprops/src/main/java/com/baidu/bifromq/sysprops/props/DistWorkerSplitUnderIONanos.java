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

package com.baidu.bifromq.sysprops.props;

import com.baidu.bifromq.sysprops.BifroMQSysProp;
import com.baidu.bifromq.sysprops.parser.LongParser;

/**
 * The maximum time in nanoseconds of I/O operations that a worker can do range split .
 */
public final class DistWorkerSplitUnderIONanos extends BifroMQSysProp<Long, LongParser> {
    public static final DistWorkerSplitUnderIONanos INSTANCE = new DistWorkerSplitUnderIONanos();

    private DistWorkerSplitUnderIONanos() {
        super("dist_worker_split_io_nanos_limit", 30_000L, LongParser.POSITIVE);
    }
}
