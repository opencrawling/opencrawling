/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
package org.opencrawling.runtime.controlplane;

import org.opencrawling.runtime.orchestrator.InMemoryRunStore;
import org.opencrawling.runtime.orchestrator.RunStore;

/** Runs kept in memory (the {@code file} control-plane store): one runtime, nothing shared, nothing kept on restart. */
class InMemoryRunStoreTest extends RunStoreContract {

    @Override
    RunStore freshStore() {
        return new InMemoryRunStore();
    }

    @Override
    RunStore otherRuntime() {
        return null;
    }

    @Override
    RunStore restarted() {
        return new InMemoryRunStore();
    }
}
