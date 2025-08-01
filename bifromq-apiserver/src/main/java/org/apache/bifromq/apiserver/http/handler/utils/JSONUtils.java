/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.bifromq.apiserver.http.handler.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import org.apache.bifromq.basekv.proto.BalancerStateSnapshot;

public class JSONUtils {
    public static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(BalancerStateSnapshot.class, new BalancerStateSnapshotJsonSerializer());
        MAPPER.registerModule(module);
    }

    private static class BalancerStateSnapshotJsonSerializer extends JsonSerializer<BalancerStateSnapshot> {
        private static final JsonFormat.Printer PRINTER = JsonFormat.printer()
            .alwaysPrintFieldsWithNoPresence()
            .preservingProtoFieldNames();

        @Override
        public void serialize(BalancerStateSnapshot value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
            String json = PRINTER.print(value);
            JsonNode jsonNode = new ObjectMapper().readTree(json);
            gen.writeObject(jsonNode);
        }
    }
}
