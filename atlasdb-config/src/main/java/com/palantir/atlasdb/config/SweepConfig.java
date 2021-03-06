/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.config;

import org.immutables.value.Value;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.palantir.atlasdb.AtlasDbConstants;

@JsonDeserialize(as = ImmutableSweepConfig.class)
@JsonSerialize(as = ImmutableSweepConfig.class)
@Value.Immutable
public class SweepConfig {
    /**
     * If true, a background thread will periodically delete cells that
     * have been overwritten or deleted. This differs from scrubbing
     * because it is an untargeted cleaning process that scans all data
     * looking for cells to delete.
     */
    @Value.Default
    public Boolean enabled() {
        return AtlasDbConstants.DEFAULT_ENABLE_SWEEP;
    }

    /**
     * The number of milliseconds to wait between each batch of cells
     * processed by the background sweeper.
     */
    @Value.Default
    public long pauseMillis() {
        return AtlasDbConstants.DEFAULT_SWEEP_PAUSE_MILLIS;
    }

    /**
     * The target number of (cell, timestamp) pairs to examine in a single run of the background sweeper.
     */
    @Value.Default
    public Integer readLimit() {
        return AtlasDbConstants.DEFAULT_SWEEP_READ_LIMIT;
    }

    /**
     * The target number of candidate (cell, timestamp) pairs to load per batch while sweeping.
     */
    @Value.Default
    public Integer candidateBatchHint() {
        return AtlasDbConstants.DEFAULT_SWEEP_CANDIDATE_BATCH_HINT;
    }

    /**
     * The target number of (cell, timestamp) pairs to delete at once while sweeping.
     */
    @Value.Default
    public Integer deleteBatchHint() {
        return AtlasDbConstants.DEFAULT_SWEEP_DELETE_BATCH_HINT;
    }

    public static SweepConfig defaultSweepConfig() {
        return ImmutableSweepConfig.builder()
                .enabled(AtlasDbConstants.DEFAULT_ENABLE_SWEEP)
                .pauseMillis(AtlasDbConstants.DEFAULT_SWEEP_PAUSE_MILLIS)
                .readLimit(AtlasDbConstants.DEFAULT_SWEEP_READ_LIMIT)
                .candidateBatchHint(AtlasDbConstants.DEFAULT_SWEEP_CANDIDATE_BATCH_HINT)
                .deleteBatchHint(AtlasDbConstants.DEFAULT_SWEEP_DELETE_BATCH_HINT)
                .build();
    }
}
