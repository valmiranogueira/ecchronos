/*
 * Copyright 2019 Telefonaktiebolaget LM Ericsson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ericsson.bss.cassandra.ecchronos.core.repair.types;

import com.ericsson.bss.cassandra.ecchronos.core.repair.state.VnodeRepairState;
import com.ericsson.bss.cassandra.ecchronos.core.utils.DriverNode;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import java.net.InetAddress;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A representation of a virtual node state.
 *
 * Primarily used to to have a type to convert to JSON.
 */
public class VirtualNodeState
{
    @NotBlank
    @Min(Long.MIN_VALUE)
    public long startToken;
    @NotBlank
    @Max(Long.MAX_VALUE)
    public long endToken;
    @NotBlank
    public Set<String> replicas;
    @NotBlank
    @Min(0)
    public long lastRepairedAtInMs;
    @NotBlank
    public boolean repaired;

    public VirtualNodeState()
    {
    }

    public VirtualNodeState(long startToken, long endToken, Set<String> replicas, long lastRepairedAtInMs, boolean repaired)
    {
        this.startToken = startToken;
        this.endToken = endToken;
        this.replicas = replicas;
        this.lastRepairedAtInMs = lastRepairedAtInMs;
        this.repaired = repaired;
    }

    public static VirtualNodeState convert(VnodeRepairState vnodeRepairState, long repairedAfter)
    {
        long startToken = vnodeRepairState.getTokenRange().start;
        long endToken = vnodeRepairState.getTokenRange().end;
        Set<String> replicas = vnodeRepairState.getReplicas().stream().map(DriverNode::getPublicAddress).map(InetAddress::getHostAddress).collect(Collectors.toSet());
        long lastRepairedAt = vnodeRepairState.lastRepairedAt();
        boolean repaired = lastRepairedAt > repairedAfter;

        return new VirtualNodeState(startToken, endToken, replicas, lastRepairedAt, repaired);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        VirtualNodeState that = (VirtualNodeState) o;
        return startToken == that.startToken &&
                endToken == that.endToken &&
                lastRepairedAtInMs == that.lastRepairedAtInMs &&
                repaired == that.repaired &&
                replicas.equals(that.replicas);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(startToken, endToken, replicas, lastRepairedAtInMs, repaired);
    }
}
