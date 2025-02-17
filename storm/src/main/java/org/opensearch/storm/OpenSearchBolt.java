/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */
 
/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.opensearch.storm;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.IRichBolt;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.opensearch.hadoop.OpenSearchHadoopException;
import org.opensearch.hadoop.rest.bulk.BulkResponse;
import org.opensearch.hadoop.rest.InitializationUtils;
import org.opensearch.hadoop.rest.RestService;
import org.opensearch.hadoop.rest.RestService.PartitionWriter;
import org.opensearch.hadoop.security.JdkUserProvider;
import org.opensearch.storm.cfg.StormSettings;
import org.opensearch.storm.security.OpenSearchClusterInfoSelector;
import org.opensearch.storm.serialization.StormTupleBytesConverter;
import org.opensearch.storm.serialization.StormTupleFieldExtractor;
import org.opensearch.storm.serialization.StormValueWriter;
import org.opensearch.storm.cfg.StormConfigurationOptions;

import static org.opensearch.hadoop.cfg.ConfigurationOptions.*;

/**
 * @deprecated Support for Apache Storm is deprecated and will be removed in the future. Consider moving to Spark or Mapreduce.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
@Deprecated
public class OpenSearchBolt implements IRichBolt {

    private transient static Log log = LogFactory.getLog(OpenSearchBolt.class);

    private Map boltConfig = new LinkedHashMap();

    private transient PartitionWriter writer;
    private transient boolean flushOnTickTuple = true;
    private transient boolean ackWrites = false;

    private transient List<Tuple> inflightTuples = null;
    private transient int numberOfEntries = 0;
    private transient OutputCollector collector;

    public OpenSearchBolt(String target) {
        this(target, null, null);
    }

    public OpenSearchBolt(String target, boolean writeAck) {
        this(target, writeAck, null);
    }

    public OpenSearchBolt(String target, Map configuration) {
        this(target, null, configuration);
    }

    private OpenSearchBolt(String target, Boolean writeAck, Map configuration) {
        log.warn("Support for Apache Storm has been deprecated and will be removed in a future release.");
        boltConfig.put(OPENSEARCH_RESOURCE_WRITE, target);

        if (writeAck != null) {
            boltConfig.put(StormConfigurationOptions.OPENSEARCH_STORM_BOLT_ACK, Boolean.toString(writeAck));
        }

        if (configuration != null) {
            boltConfig.putAll(configuration);
        }
    }

    @Override
    public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;

        LinkedHashMap copy = new LinkedHashMap(conf);
        copy.putAll(boltConfig);

        StormSettings settings = new StormSettings(copy);
        flushOnTickTuple = settings.getStormTickTupleFlush();
        ackWrites = settings.getStormBoltAck();

        // trigger manual flush
        if (ackWrites) {
            settings.setProperty(OPENSEARCH_BATCH_FLUSH_MANUAL, Boolean.TRUE.toString());

            // align Bolt / opensearch-hadoop batch settings
            numberOfEntries = settings.getStormBulkSize();
            settings.setProperty(OPENSEARCH_BATCH_SIZE_ENTRIES, String.valueOf(numberOfEntries));

            inflightTuples = new ArrayList<Tuple>(numberOfEntries + 1);
        }

        int totalTasks = context.getComponentTasks(context.getThisComponentId()).size();

        InitializationUtils.setValueWriterIfNotSet(settings, StormValueWriter.class, log);
        InitializationUtils.setBytesConverterIfNeeded(settings, StormTupleBytesConverter.class, log);
        InitializationUtils.setFieldExtractorIfNotSet(settings, StormTupleFieldExtractor.class, log);
        InitializationUtils.setUserProviderIfNotSet(settings, JdkUserProvider.class, log);

        OpenSearchClusterInfoSelector.populate(settings);

        writer = RestService.createWriter(settings, context.getThisTaskIndex(), totalTasks, log);
    }

    @Override
    public void execute(Tuple input) {
        if (flushOnTickTuple && TupleUtils.isTickTuple(input)) {
            flush();
            return;
        }
        if (ackWrites) {
            inflightTuples.add(input);
        }
        try {
            writer.repository.writeToIndex(input);

            // manual flush in case of ack writes - handle it here.
            if (numberOfEntries > 0 && inflightTuples.size() >= numberOfEntries) {
                flush();
            }

            if (!ackWrites) {
                collector.ack(input);
            }
        } catch (RuntimeException ex) {
            if (!ackWrites) {
                collector.fail(input);
            }
            throw ex;
        }
    }

    private void flush() {
        if (ackWrites) {
            flushWithAck();
        }
        else {
            flushNoAck();
        }
    }

    private void flushWithAck() {
        BitSet flush = new BitSet();

        try {
            List<BulkResponse.BulkError> documentErrors = writer.repository.tryFlush().getDocumentErrors();
            // get set of document positions that failed.
            for (BulkResponse.BulkError documentError : documentErrors) {
                flush.set(documentError.getOriginalPosition());
            }
        } catch (OpenSearchHadoopException ex) {
            // fail all recorded tuples
            for (Tuple input : inflightTuples) {
                collector.fail(input);
            }
            inflightTuples.clear();
            throw ex;
        }

        for (int index = 0; index < inflightTuples.size(); index++) {
            Tuple tuple = inflightTuples.get(index);
            // bit set means the entry hasn't been removed and thus wasn't written to OpenSearch
            if (flush.get(index)) {
                collector.fail(tuple);
            }
            else {
                collector.ack(tuple);
            }
        }

        // clear everything in bulk to prevent 'noisy' remove()
        inflightTuples.clear();
    }

    private void flushNoAck() {
        writer.repository.flush();
    }

    @Override
    public void cleanup() {
        if (writer != null) {
            try {
                flush();
            } finally {
                writer.close();
                writer = null;
            }
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {}

    @Override
    public Map<String, Object> getComponentConfiguration() {
        return null;
    }
}