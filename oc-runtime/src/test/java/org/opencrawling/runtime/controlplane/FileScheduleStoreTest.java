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
package org.opencrawling.runtime.controlplane;

import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.runtime.scheduler.FileScheduleStore;
import org.opencrawling.runtime.scheduler.ScheduleStore;

import java.nio.file.Path;

/** Contract test for {@link FileScheduleStore} across runtime restarts (#197). */
class FileScheduleStoreTest extends ScheduleStoreContract {

    @TempDir
    Path dataDir;

    @Override
    ScheduleStore freshStore() {
        return new FileScheduleStore(ControlPlaneStore.files(), dataDir);
    }

    @Override
    ScheduleStore restarted() {
        return new FileScheduleStore(ControlPlaneStore.files(), dataDir);
    }
}
