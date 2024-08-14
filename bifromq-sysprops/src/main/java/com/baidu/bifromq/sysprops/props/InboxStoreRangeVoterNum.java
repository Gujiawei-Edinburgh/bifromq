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
import com.baidu.bifromq.sysprops.parser.IntegerParser;

/**
 * The number of voters of the replication group for a range in the inbox store.
 */
public final class InboxStoreRangeVoterNum extends BifroMQSysProp<Integer, IntegerParser> {
    public static final InboxStoreRangeVoterNum INSTANCE = new InboxStoreRangeVoterNum();

    private InboxStoreRangeVoterNum() {
        super("inbox_store_range_voter_count", 1, IntegerParser.POSITIVE);
    }
}