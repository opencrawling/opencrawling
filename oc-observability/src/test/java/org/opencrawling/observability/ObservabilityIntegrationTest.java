/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

public class ObservabilityIntegrationTest {

    @Test
    public void testEmitTraceToCollector() throws Exception {
        System.out.println("Checking if OpenTelemetry Collector is reachable...");
        boolean collectorAvailable = false;
        try (Socket socket = new Socket("localhost", 4317)) {
            collectorAvailable = true;
        } catch (Exception e) {
            System.out.println("OTel Collector is not running on localhost:4317. Skipping active integration test.");
        }

        if (!collectorAvailable) {
            return;
        }

        System.out.println("Initializing test OpenTelemetry SDK targeting local collector...");
        
        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint("http://localhost:4317")
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .setResource(Resource.getDefault().merge(Resource.create(
                        Attributes.of(AttributeKey.stringKey("service.name"), "opencrawling-observability-integration-test-service")
                )))
                .build();

        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        Tracer tracer = openTelemetry.getTracer("org.opencrawling.observability.test");

        System.out.println("Starting test trace span...");
        Span span = tracer.spanBuilder("test-integration-span")
                .setAttribute("integration.test", "true")
                .setAttribute("crawling.test.status", "observability-verified")
                .startSpan();

        try {
            System.out.println("Processing sample simulated work inside trace context...");
            Thread.sleep(100);
            span.addEvent("simulated-event-milestone");
        } finally {
            span.end();
            System.out.println("Ended test trace span.");
        }

        tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
        System.out.println("OTel SDK shutdown successfully. Spans flushed.");
    }
}
