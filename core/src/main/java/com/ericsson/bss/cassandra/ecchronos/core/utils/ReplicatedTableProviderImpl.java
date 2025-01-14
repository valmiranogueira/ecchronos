/*
 * Copyright 2018 Telefonaktiebolaget LM Ericsson
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
package com.ericsson.bss.cassandra.ecchronos.core.utils;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class ReplicatedTableProviderImpl implements ReplicatedTableProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(ReplicatedTableProviderImpl.class);

    private static final String STRATEGY_CLASS = "class";
    private static final String SIMPLE_STRATEGY = "org.apache.cassandra.locator.SimpleStrategy";
    private static final String NETWORK_TOPOLOGY_STRATEGY = "org.apache.cassandra.locator.NetworkTopologyStrategy";

    private static final String SIMPLE_STRATEGY_REPLICATION_FACTOR = "replication_factor";

    private static final String SYSTEM_AUTH_KEYSPACE = "system_auth";

    private final Node myLocalNode;
    private final CqlSession mySession;
    private final TableReferenceFactory myTableReferenceFactory;

    public ReplicatedTableProviderImpl(Node node, CqlSession session, TableReferenceFactory tableReferenceFactory)
    {
        myLocalNode = node;
        mySession = session;
        myTableReferenceFactory = tableReferenceFactory;
    }

    @Override public Set<TableReference> getAll()
    {
        return mySession.getMetadata().getKeyspaces().values().stream()
                .filter(k -> accept(k.getName().asInternal()))
                .flatMap(k -> k.getTables().values().stream())
                .map(tb -> myTableReferenceFactory.forTable(tb.getKeyspace().asInternal(), tb.getName().asInternal()))
                .collect(Collectors.toSet());
    }

    @Override
    public boolean accept(String keyspace)
    {
        if (keyspace.startsWith("system") && !SYSTEM_AUTH_KEYSPACE.equals(keyspace))
        {
            return false;
        }

        Optional<KeyspaceMetadata> keyspaceMetadata = Metadata.getKeyspace(mySession, keyspace);

        if (keyspaceMetadata.isPresent())
        {
            Map<String, String> replication = keyspaceMetadata.get().getReplication();
            String replicationClass = replication.get(STRATEGY_CLASS);

            switch (replicationClass)
            {
                case SIMPLE_STRATEGY:
                    return validateSimpleStrategy(replication);
                case NETWORK_TOPOLOGY_STRATEGY:
                    return validateNetworkTopologyStrategy(keyspace, replication);
                default:
                    LOG.warn("Replication strategy of type {} is not supported", replicationClass);
                    break;
            }
        }

        return false;
    }

    private boolean validateSimpleStrategy(Map<String, String> replication)
    {
        int replicationFactor = Integer.parseInt(replication.get(SIMPLE_STRATEGY_REPLICATION_FACTOR));

        return replicationFactor > 1;
    }

    private boolean validateNetworkTopologyStrategy(String keyspace, Map<String, String> replication)
    {
        String localDc = myLocalNode.getDatacenter();

        if (localDc == null)
        {
            LOG.error("Local data center is not defined, ignoring keyspace {}", keyspace);
            return false;
        }

        if (!replication.containsKey(localDc))
        {
            LOG.debug("Keyspace {} not replicated by local node, ignoring.", keyspace);
            return false;
        }

        return definedReplicationInNetworkTopologyStrategy(replication) > 1;
    }

    private int definedReplicationInNetworkTopologyStrategy(Map<String, String> replication)
    {
        int replicationFactor = 0;

        for (Map.Entry<String, String> replicationEntry : replication.entrySet())
        {
            if (!STRATEGY_CLASS.equals(replicationEntry.getKey()))
            {
                replicationFactor += Integer.parseInt(replicationEntry.getValue());
            }
        }

        return replicationFactor;
    }
}
