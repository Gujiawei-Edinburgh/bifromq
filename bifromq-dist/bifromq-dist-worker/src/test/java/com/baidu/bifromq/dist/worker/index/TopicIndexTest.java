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

package com.baidu.bifromq.dist.worker.index;

import static org.testng.Assert.assertEquals;

import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class TopicIndexTest {
    private TopicIndex<String> topicIndex;

    @BeforeMethod
    public void setUp() {
        topicIndex = new TopicIndex<>();
    }

    @Test
    public void testMatch() {
        add("/", "/a", "/b", "a", "a/", "a/b", "a/b/c", "$a", "$a/", "$a/b").join();
        assertMatch(topicIndex.match("/"), "/");
        assertMatch(topicIndex.match("/a"), "/a");
        assertMatch(topicIndex.match("/b"), "/b");
        assertMatch(topicIndex.match("a"), "a");
        assertMatch(topicIndex.match("a/"), "a/");
        assertMatch(topicIndex.match("a/b"), "a/b");
        assertMatch(topicIndex.match("a/b/c"), "a/b/c");
        assertMatch(topicIndex.match("$a"), "$a");
        assertMatch(topicIndex.match("$a/"), "$a/");
        assertMatch(topicIndex.match("$a/b"), "$a/b");

        assertMatch(topicIndex.match(""));
        assertMatch(topicIndex.match("fakeTopic"));

        assertMatch(topicIndex.match("+"), "a");
        assertMatch(topicIndex.match("#"), "/", "/a", "/b", "a", "a/", "a/b", "a/b/c");

        assertMatch(topicIndex.match("/+"), "/", "/a", "/b");
        assertMatch(topicIndex.match("/#"), "/", "/a", "/b");

        assertMatch(topicIndex.match("a/+"), "a/", "a/b");
        assertMatch(topicIndex.match("a/#"), "a", "a/", "a/b", "a/b/c");

        assertMatch(topicIndex.match("$a/+"), "$a/", "$a/b");
        assertMatch(topicIndex.match("$a/#"), "$a", "$a/", "$a/b");
    }

    @Test
    public void testRemove() {
        add("/", "/a", "/b", "a", "a/", "a/b", "a/b/c", "$a", "$a/", "$a/b").join();
        topicIndex.remove("/", "/");
        assertMatch(topicIndex.match("/"));
        topicIndex.remove("/a", "/a");
        assertMatch(topicIndex.match("/a"));
        topicIndex.remove("/b", "/b");
        assertMatch(topicIndex.match("/b"));
        topicIndex.remove("a", "a");
        assertMatch(topicIndex.match("a"));
        topicIndex.remove("a/", "a/");
        assertMatch(topicIndex.match("a/"));
        topicIndex.remove("a/b", "a/b");
        assertMatch(topicIndex.match("a/b"));
        topicIndex.remove("a/b/c", "a/b/c");
        assertMatch(topicIndex.match("a/b/c"));
        topicIndex.remove("$a", "$a");
        assertMatch(topicIndex.match("$a"));
        topicIndex.remove("$a/", "$a/");
        assertMatch(topicIndex.match("$a/"));
        topicIndex.remove("$a/b", "$a/b");
        assertMatch(topicIndex.match("$a/b"));

        assertMatch(topicIndex.match("#"));
    }


    @Test
    public void testEdgeCases() {
        add("/", "/").join();
        assertMatch(topicIndex.match("#"), "/");
    }

    private CompletableFuture<Void> add(String... topics) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String topic : topics) {
            futures.add(CompletableFuture.runAsync(() -> topicIndex.add(topic, topic)));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private void assertMatch(List<String> matches, String... expected) {
        assertEquals(Sets.newHashSet(matches), Set.of(expected));
    }
}