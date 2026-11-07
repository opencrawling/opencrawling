/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.core.security;

import java.util.List;

public record SecurityConfig(
    boolean inheritanceEnabled,
    List<PermissionRule> permissions
) {
    /**
     * No permission rules: what a document gets when its source reported no security at all. It is never public;
     * readers decide on the legacy {@code acl} string alone, and a blank {@code acl} grants no one.
     */
    public static SecurityConfig none() {
        return new SecurityConfig(false, List.of());
    }

    public static SecurityConfig createPublic() {
        return new SecurityConfig(false, List.of(
            new PermissionRule("public", "public", "Public Access", "read")
        ));
    }
}
