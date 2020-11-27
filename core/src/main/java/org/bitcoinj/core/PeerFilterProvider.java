/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.core;


/**
 * An interface which provides the information required to properly filter data downloaded from Peers.
 * Note that an implementer is responsible for calling {@link PeerGroup#recalculateFastCatchupAndFilter(org.bitcoinj.core.PeerGroup.FilterRecalculateMode)}
 * whenever a change occurs which effects the data provided via this interface.
 */
public interface PeerFilterProvider {
    /**
     * Returns the earliest timestamp (seconds since epoch) for which full/bloom-filtered blocks must be downloaded.
     * Blocks with timestamps before this time will only have headers downloaded. 0 requires that all blocks be
     * downloaded, and thus this should default to {@link System#currentTimeMillis()}/1000.
     */
    long getEarliestKeyCreationTime();

    /**
     * Called on all registered filter p