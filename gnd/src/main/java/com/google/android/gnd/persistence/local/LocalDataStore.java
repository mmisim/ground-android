/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gnd.persistence.local;

import com.google.android.gnd.persistence.shared.FeatureMutation;
import com.google.android.gnd.vo.Feature;
import com.google.android.gnd.vo.Project;
import com.google.common.collect.ImmutableSet;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;

/**
 * Provides access to local persistent data store, the canonical store for latest state and
 * mutations queued for sync with remote. Local changes are saved here before being written to the
 * remote data store, and both local and remote updates are always persisted here before being made
 * visible to the user.
 */
public interface LocalDataStore {
  /**
   * Applies the specified {@link FeatureMutation} to the local data store, appending the mutation
   * to the local queue for remote sync. It is assumed to be a new, unsaved instance, and as such
   * its {@code changeId} is ignored.
   */
  Completable applyAndEnqueue(FeatureMutation mutation);

  /**
   * Returns a long-lived stream that emits the full set of features for a project on subscribe, and
   * continues to return the full set each time a feature is added/changed/removed. The full set is
   * returned rather than deltas for simplicity and to implement a fully reactive UI in which each
   * update is idempotent.
   */
  Flowable<ImmutableSet<Feature>> getFeaturesOnceAndStream(Project project);

  /** Returns the feature with the specified UUID from the local data store, if found. */
  Maybe<Feature> getFeature(Project project, String featureId);
}
