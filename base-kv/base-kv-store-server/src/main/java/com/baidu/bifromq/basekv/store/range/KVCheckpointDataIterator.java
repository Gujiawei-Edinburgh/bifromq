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

package com.baidu.bifromq.basekv.store.range;

import com.baidu.bifromq.basekv.localengine.IKVSpaceIterator;

class KVCheckpointDataIterator extends KVIterator implements IKVCheckpointIterator {
    private final IKVSpaceIterator kvSpaceIterator;

    KVCheckpointDataIterator(IKVSpaceIterator kvSpaceIterator) {
        super(kvSpaceIterator);
        this.kvSpaceIterator = kvSpaceIterator;
    }

    @Override
    public void close() {
        kvSpaceIterator.close();
    }
}
