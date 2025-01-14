/*
 * Copyright 2020 Telefonaktiebolaget LM Ericsson
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
package com.ericsson.bss.cassandra.ecchronos.core.repair;

import java.io.Closeable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.ericsson.bss.cassandra.ecchronos.core.utils.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ericsson.bss.cassandra.ecchronos.core.exceptions.EcChronosException;
import com.ericsson.bss.cassandra.ecchronos.core.JmxProxyFactory;
import com.ericsson.bss.cassandra.ecchronos.core.metrics.TableRepairMetrics;
import com.ericsson.bss.cassandra.ecchronos.core.repair.state.RepairHistory;
import com.ericsson.bss.cassandra.ecchronos.core.repair.state.ReplicationState;
import com.ericsson.bss.cassandra.ecchronos.core.scheduling.ScheduleManager;
import com.ericsson.bss.cassandra.ecchronos.core.scheduling.ScheduledJob;
import com.ericsson.bss.cassandra.ecchronos.core.utils.TableReference;

/**
 * A factory creating {@link OnDemandRepairJob}'s for tables.
 */
public class OnDemandRepairSchedulerImpl implements OnDemandRepairScheduler, Closeable
{
    private static final Logger LOG = LoggerFactory.getLogger(OnDemandRepairSchedulerImpl.class);
    private static final int ONGOING_JOBS_PERIOD_SECONDS = 10;

    private final Map<UUID, OnDemandRepairJob> myScheduledJobs = new HashMap<>();
    private final Object myLock = new Object();

    private final JmxProxyFactory myJmxProxyFactory;
    private final TableRepairMetrics myTableRepairMetrics;
    private final ScheduleManager myScheduleManager;
    private final ReplicationState myReplicationState;
    private final RepairLockType myRepairLockType;
    private final CqlSession mySession;
    private final RepairConfiguration myRepairConfiguration;
    private final RepairHistory myRepairHistory;
    private final OnDemandStatus myOnDemandStatus;
    private final ScheduledExecutorService myExecutor = Executors.newSingleThreadScheduledExecutor();

    private OnDemandRepairSchedulerImpl(Builder builder)
    {
        myJmxProxyFactory = builder.myJmxProxyFactory;
        myTableRepairMetrics = builder.myTableRepairMetrics;
        myScheduleManager = builder.myScheduleManager;
        myReplicationState = builder.myReplicationState;
        myRepairLockType = builder.repairLockType;
        mySession = builder.session;
        myRepairConfiguration = builder.repairConfiguration;
        myRepairHistory = builder.repairHistory;
        myOnDemandStatus = builder.onDemandStatus;
        myExecutor.scheduleAtFixedRate(() -> getOngoingJobs(), 0, ONGOING_JOBS_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private void getOngoingJobs()
    {
        try
        {
            Set<OngoingJob> ongoingJobs = myOnDemandStatus.getOngoingJobs(myReplicationState);
            ongoingJobs.forEach(j -> scheduleOngoingJob(j));
        }
        catch (Exception e)
        {
            LOG.info("Failed to get ongoing ondemand jobs: {}, automatic retry in {}s", e.getMessage(),
                    ONGOING_JOBS_PERIOD_SECONDS);
        }
    }

    @Override
    public void close()
    {
        synchronized (myLock)
        {
            for (ScheduledJob job : myScheduledJobs.values())
            {
                descheduleTable(job);
            }
            myScheduledJobs.clear();
            myExecutor.shutdown();
        }
    }

    @Override
    public List<OnDemandRepairJobView> scheduleClusterWideJob(TableReference tableReference) throws EcChronosException
    {
        OnDemandRepairJobView currentJob = scheduleJob(tableReference, true);
        return getAllClusterWideRepairJobs().stream()
                .filter(j -> j.getId().equals(currentJob.getId()))
                .collect(Collectors.toList());
    }

    @Override
    public OnDemandRepairJobView scheduleJob(TableReference tableReference) throws EcChronosException
    {
        return scheduleJob(tableReference, false);
    }

    private OnDemandRepairJobView scheduleJob(TableReference tableReference, boolean isClusterWide) throws EcChronosException
    {
        synchronized (myLock)
        {
            if (tableReference != null)
            {
                Optional<KeyspaceMetadata> ks = Metadata.getKeyspace(mySession, tableReference.getKeyspace());
                if (ks.isPresent() && Metadata.getTable(ks.get(), tableReference.getTable()).isPresent())
                {
                    OnDemandRepairJob job = getRepairJob(tableReference, isClusterWide);
                    myScheduledJobs.put(job.getId(), job);
                    myScheduleManager.schedule(job);
                    return job.getView();
                }
            }
            throw new EcChronosException("Keyspace and/or table does not exist");
        }
    }

    private void scheduleOngoingJob(OngoingJob ongoingJob)
    {
        synchronized (myLock)
        {
            OnDemandRepairJob job = getOngoingRepairJob(ongoingJob);
            if(myScheduledJobs.putIfAbsent(job.getId(), job) == null)
            {
                LOG.info("Scheduling ongoing job: {}", job.getId());
                myScheduleManager.schedule(job);
            }
        }
    }

    public List<OnDemandRepairJobView> getActiveRepairJobs()
    {
        synchronized (myLock)
        {
            return myScheduledJobs.values().stream()
                    .map(OnDemandRepairJob::getView)
                    .collect(Collectors.toList());
        }
    }

    @Override
    public List<OnDemandRepairJobView> getAllClusterWideRepairJobs()
    {
        return myOnDemandStatus.getAllClusterWideJobs().stream()
                .map(job -> getOngoingRepairJob(job))
                .map(OnDemandRepairJob::getView)
                .collect(Collectors.toList());
    }

    @Override
    public List<OnDemandRepairJobView> getAllRepairJobs()
    {
        return myOnDemandStatus.getAllJobs(myReplicationState).stream()
                .map(job -> getOngoingRepairJob(job))
                .map(OnDemandRepairJob::getView)
                .collect(Collectors.toList());
    }

    private void removeScheduledJob(UUID id)
    {
        synchronized (myLock)
        {
            ScheduledJob job = myScheduledJobs.remove(id);
            myScheduleManager.deschedule(job);
        }
    }

    private void descheduleTable(ScheduledJob job)
    {
        if (job != null)
        {
            myScheduleManager.deschedule(job);
        }
    }

    private OnDemandRepairJob getRepairJob(TableReference tableReference, boolean isClusterWide)
    {
        OngoingJob ongoingJob = new OngoingJob.Builder()
                .withOnDemandStatus(myOnDemandStatus)
                .withTableReference(tableReference)
                .withReplicationState(myReplicationState)
                .withHostId(myOnDemandStatus.getHostId())
                .build();
        if (isClusterWide)
        {
            ongoingJob.startClusterWideJob();
        }
        OnDemandRepairJob job = new OnDemandRepairJob.Builder()
                .withJmxProxyFactory(myJmxProxyFactory)
                .withTableRepairMetrics(myTableRepairMetrics)
                .withRepairLockType(myRepairLockType)
                .withOnFinished(this::removeScheduledJob)
                .withRepairConfiguration(myRepairConfiguration)
                .withRepairHistory(myRepairHistory)
                .withOngoingJob(ongoingJob)
                .build();
        return job;
    }

    private OnDemandRepairJob getOngoingRepairJob(OngoingJob ongoingJob)
    {
        OnDemandRepairJob job = new OnDemandRepairJob.Builder()
                .withJmxProxyFactory(myJmxProxyFactory)
                .withTableRepairMetrics(myTableRepairMetrics)
                .withRepairLockType(myRepairLockType)
                .withOnFinished(this::removeScheduledJob)
                .withRepairConfiguration(myRepairConfiguration)
                .withRepairHistory(myRepairHistory)
                .withOngoingJob(ongoingJob)
                .build();
        return job;
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static class Builder
    {
        private JmxProxyFactory myJmxProxyFactory;
        private TableRepairMetrics myTableRepairMetrics;
        private ScheduleManager myScheduleManager;
        private ReplicationState myReplicationState;
        private RepairLockType repairLockType;
        private CqlSession session;
        private RepairConfiguration repairConfiguration;
        private RepairHistory repairHistory;
        private OnDemandStatus onDemandStatus;

        public Builder withJmxProxyFactory(JmxProxyFactory jmxProxyFactory)
        {
            myJmxProxyFactory = jmxProxyFactory;
            return this;
        }

        public Builder withTableRepairMetrics(TableRepairMetrics tableRepairMetrics)
        {
            myTableRepairMetrics = tableRepairMetrics;
            return this;
        }

        public Builder withScheduleManager(ScheduleManager scheduleManager)
        {
            myScheduleManager = scheduleManager;
            return this;
        }

        public Builder withReplicationState(ReplicationState replicationState)
        {
            myReplicationState = replicationState;
            return this;
        }

        public Builder withRepairLockType(RepairLockType repairLockType)
        {
            this.repairLockType = repairLockType;
            return this;
        }

        public Builder withSession(CqlSession session)
        {
            this.session = session;
            return this;
        }

        public Builder withRepairConfiguration(RepairConfiguration repairConfiguration)
        {
            this.repairConfiguration = repairConfiguration;
            return this;
        }

        public Builder withRepairHistory(RepairHistory repairHistory)
        {
            this.repairHistory = repairHistory;
            return this;
        }

        public Builder withOnDemandStatus(OnDemandStatus onDemandStatus)
        {
            this.onDemandStatus = onDemandStatus;
            return this;
        }

        public OnDemandRepairSchedulerImpl build()
        {
            return new OnDemandRepairSchedulerImpl(this);
        }
    }
}
