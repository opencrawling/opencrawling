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
package org.opencrawling.runtime.api.v1;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One page of a collection. {@code page.nextCursor} is null on the last page; pass it as {@code cursor} for the next.
 */
@Schema(description = "One page of a collection")
public record Page<T>(
        @Schema(description = "The items on this page") List<T> items,
        @Schema(description = "Paging information") PageInfo page) {

    @Schema(description = "Paging information")
    public record PageInfo(
            @Schema(description = "The page size used", example = "50") int limit,
            @Schema(description = "Cursor of the next page; null on the last page") String nextCursor,
            @Schema(description = "Items in the whole collection after filtering", example = "120") int totalItems) {}
}
