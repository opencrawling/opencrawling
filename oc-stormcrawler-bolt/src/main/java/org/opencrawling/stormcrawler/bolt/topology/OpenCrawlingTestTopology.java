/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.stormcrawler.bolt.topology;

import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;
import org.apache.stormcrawler.Constants;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.persistence.Status;
import org.opencrawling.stormcrawler.bolt.OpenCrawlingBolt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Apache Storm test topology executing OpenCrawlingBolt in an active Storm cluster.
 * Emits parsed web page content tuples and status deletion tuples, verifying that
 * OpenCrawlingBolt normalizes and dispatches them correctly in a distributed Storm topology.
 */
public class OpenCrawlingTestTopology {

    private static final Logger log = LoggerFactory.getLogger(OpenCrawlingTestTopology.class);

    public static final String SPOUT_ID = "test-crawl-spout";
    public static final String BOLT_ID = "opencrawling-bolt";
    public static final String DEFAULT_TOPOLOGY_NAME = "opencrawling-bolt-topology";

    public static class TestCrawlSpout extends BaseRichSpout {

        private static final Logger spoutLog = LoggerFactory.getLogger(TestCrawlSpout.class);

        private SpoutOutputCollector collector;
        private int emittedCount = 0;
        private static final int MAX_EMISSIONS = 5;

        @Override
        public void open(Map<String, Object> conf, TopologyContext context, SpoutOutputCollector collector) {
            this.collector = collector;
            spoutLog.info("TestCrawlSpout opened successfully (taskId={})", context.getThisTaskId());
        }

        @Override
        public void nextTuple() {
            if (emittedCount >= MAX_EMISSIONS) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                return;
            }

            emittedCount++;
            String url = "https://example.com/docs/stormcrawler-test-page-" + emittedCount;

            // Emit content tuple on default stream
            Metadata metadata = new Metadata();
            metadata.setValue("title", "StormCrawler Test Document " + emittedCount);
            metadata.setValue("parse.Content-Type", "text/html");
            metadata.setValue("http.status", "200");
            metadata.setValue("canonical.url", url);

            byte[] content = ("<html><head><title>Test Page " + emittedCount + "</title></head>"
                    + "<body><h1>StormCrawler Integration Test</h1>"
                    + "<p>OpenCrawling Bolt verification content for page " + emittedCount + "</p></body></html>")
                    .getBytes(StandardCharsets.UTF_8);

            String text = "StormCrawler Integration Test OpenCrawling Bolt verification content for page " + emittedCount;

            collector.emit(new Values(url, content, metadata, text));
            spoutLog.info("Emitted parsed content tuple for URL: {}", url);

            // On page 3, emit an HTTP 404 deletion signal on the status stream
            if (emittedCount == 3) {
                String deletedUrl = "https://example.com/docs/stormcrawler-deleted-page";
                Metadata delMetadata = new Metadata();
                delMetadata.setValue("http.status", "404");
                delMetadata.setValue("stormcrawler.status", "FETCH_ERROR");

                collector.emit(Constants.StatusStreamName, new Values(deletedUrl, Status.FETCH_ERROR, delMetadata));
                spoutLog.info("Emitted status deletion tuple for URL: {}", deletedUrl);
            }
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("url", "content", "metadata", "text"));
            declarer.declareStream(Constants.StatusStreamName, new Fields("url", "status", "metadata"));
        }
    }

    public static void main(String[] args) throws Exception {
        String topologyName = args.length > 0 && !args[0].isBlank() ? args[0] : DEFAULT_TOPOLOGY_NAME;
        String endpoint = args.length > 1 && !args[1].isBlank() ? args[1] : "http://localhost:8080/api/v1/ingest/ois";
        String transportMode = args.length > 2 && !args[2].isBlank() ? args[2] : "MEMORY";

        log.info("Configuring OpenCrawlingTestTopology: name='{}', endpoint='{}', transport='{}'",
                topologyName, endpoint, transportMode);

        TopologyBuilder builder = new TopologyBuilder();
        builder.setSpout(SPOUT_ID, new TestCrawlSpout(), 1);

        OpenCrawlingBolt bolt = new OpenCrawlingBolt()
                .withTargetEndpoint(endpoint)
                .withTransportMode(transportMode)
                .withEmitDeletions(true)
                .withInstanceId("docker-storm-cluster");

        builder.setBolt(BOLT_ID, bolt, 1)
                .shuffleGrouping(SPOUT_ID)
                .shuffleGrouping(SPOUT_ID, Constants.StatusStreamName);

        Config conf = new Config();
        conf.setNumWorkers(1);
        conf.setMessageTimeoutSecs(30);
        conf.setDebug(false);

        log.info("Submitting topology '{}' to Apache Storm Nimbus...", topologyName);
        StormSubmitter.submitTopology(topologyName, conf, builder.createTopology());
        log.info("Topology '{}' submitted successfully!", topologyName);
    }
}
