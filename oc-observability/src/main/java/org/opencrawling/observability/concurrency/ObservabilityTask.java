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
package org.opencrawling.observability.concurrency;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.concurrent.Callable;

/**
 * Utility to propagate OpenTelemetry trace context across virtual thread boundaries.
 * Wraps Runnable and Callable tasks to restore OTel context inside the task execution thread.
 */
public final class ObservabilityTask {

    private ObservabilityTask() {}

    /**
     * Decorates a Callable to capture the current trace context and restore it in the execution thread.
     */
    public static <V> Callable<V> observed(Callable<V> task) {
        Context parentContext = Context.current();
        return () -> {
            try (Scope scope = parentContext.makeCurrent()) {
                return task.call();
            }
        };
    }

    /**
     * Decorates a Runnable to capture the current trace context and restore it in the execution thread.
     */
    public static Runnable observed(Runnable task) {
        Context parentContext = Context.current();
        return () -> {
            try (Scope scope = parentContext.makeCurrent()) {
                task.run();
            }
        };
    }
}
