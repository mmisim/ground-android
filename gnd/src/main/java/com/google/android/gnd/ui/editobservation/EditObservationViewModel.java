/*
 * Copyright 2018 Google LLC
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

package com.google.android.gnd.ui.editobservation;

import static androidx.lifecycle.LiveDataReactiveStreams.fromPublisher;
import static java8.util.stream.StreamSupport.stream;

import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import androidx.databinding.ObservableArrayMap;
import androidx.databinding.ObservableMap;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.akaita.java.rxjava2debug.RxJava2Debug;
import com.google.android.gnd.GndApplication;
import com.google.android.gnd.R;
import com.google.android.gnd.model.form.Element;
import com.google.android.gnd.model.form.Element.Type;
import com.google.android.gnd.model.form.Field;
import com.google.android.gnd.model.form.Form;
import com.google.android.gnd.model.observation.Observation;
import com.google.android.gnd.model.observation.ObservationMutation;
import com.google.android.gnd.model.observation.Response;
import com.google.android.gnd.model.observation.ResponseDelta;
import com.google.android.gnd.model.observation.ResponseMap;
import com.google.android.gnd.model.observation.TextResponse;
import com.google.android.gnd.repository.DataRepository;
import com.google.android.gnd.rx.Event;
import com.google.android.gnd.rx.Nil;
import com.google.android.gnd.system.AuthenticationManager;
import com.google.android.gnd.ui.common.AbstractViewModel;
import com.google.common.collect.ImmutableList;
import io.reactivex.Single;
import io.reactivex.processors.BehaviorProcessor;
import io.reactivex.processors.PublishProcessor;
import java8.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;

// TODO: Save draft to local db on each change.
public class EditObservationViewModel extends AbstractViewModel {
  private static final String TAG = EditObservationViewModel.class.getSimpleName();
  // TODO: Move out of id and into fragment args.
  private static final String ADD_OBSERVATION_ID_PLACEHOLDER = "NEW_RECORD";

  // Injected inputs.

  private final DataRepository dataRepository;
  private final AuthenticationManager authManager;
  private final Resources resources;

  // Input events.

  /** Arguments passed in from view on initialize(). */
  private final BehaviorProcessor<EditObservationFragmentArgs> viewArgs =
      BehaviorProcessor.create();

  /** "Save" button clicks. */
  private final PublishProcessor<Nil> saveClicks = PublishProcessor.create();

  // View state streams.

  /** Form definition, loaded when view is initialized. */
  private final LiveData<Form> form;

  /** Toolbar title, based on whether user is adding new or editing existing observation. */
  private final MutableLiveData<String> toolbarTitle = new MutableLiveData<>();

  /** Original form responses, loaded when view is initialized. */
  private final ObservableMap<String, Response> responses = new ObservableArrayMap<>();

  /** Form validation errors, updated when existing for loaded and when responses change. */
  private final ObservableMap<String, String> validationErrors = new ObservableArrayMap<>();

  /** Visibility of process widget shown while loading. */
  private final MutableLiveData<Integer> loadingSpinnerVisibility =
      new MutableLiveData<>(View.GONE);

  /** Visibility of "Save" button hidden while loading. */
  private final MutableLiveData<Integer> saveButtonVisibility = new MutableLiveData<>(View.GONE);

  /** Visibility of saving progress dialog, show saving. */
  private final MutableLiveData<Integer> savingProgressVisibility =
      new MutableLiveData<>(View.GONE);

  /** Outcome of user clicking "Save". */
  private final LiveData<Event<SaveResult>> saveResults;

  /** Possible outcomes of user clicking "Save". */
  enum SaveResult {
    HAS_VALIDATION_ERRORS,
    NO_CHANGES_TO_SAVE,
    SAVED
  }

  // Internal state.

  /** Observation state loaded when view is initialized. */
  @Nullable private Observation originalObservation;

  /** True if the observation is being added, false if editing an existing one. */
  private boolean isNew;

  @Inject
  EditObservationViewModel(
      GndApplication application,
      DataRepository dataRepository,
      AuthenticationManager authenticationManager) {
    this.resources = application.getResources();
    this.dataRepository = dataRepository;
    this.authManager = authenticationManager;
    this.form = fromPublisher(viewArgs.switchMapSingle(this::onInitialize));
    this.saveResults = fromPublisher(saveClicks.switchMapSingle(__ -> onSave()));
  }

  public LiveData<Form> getForm() {
    return form;
  }

  public LiveData<Integer> getLoadingSpinnerVisibility() {
    return loadingSpinnerVisibility;
  }

  public LiveData<Integer> getSaveButtonVisibility() {
    return saveButtonVisibility;
  }

  public LiveData<Integer> getSavingProgressVisibility() {
    return savingProgressVisibility;
  }

  public LiveData<String> getToolbarTitle() {
    return toolbarTitle;
  }

  public LiveData<Event<SaveResult>> getSaveResults() {
    return saveResults;
  }

  public void initialize(EditObservationFragmentArgs args) {
    viewArgs.onNext(args);
  }

  public ObservableMap<String, Response> getResponses() {
    return responses;
  }

  public Optional<Response> getResponse(String fieldId) {
    return Optional.ofNullable(responses.get(fieldId));
  }

  public ObservableMap<String, String> getValidationErrors() {
    return validationErrors;
  }

  public void onTextChanged(Field field, String text) {
    Log.v(TAG, "onTextChanged: " + field.getId());

    onResponseChanged(field, TextResponse.fromString(text));
  }

  public void onResponseChanged(Field field, Optional<Response> newResponse) {
    Log.v(
        TAG, "onResponseChanged: " + field.getId() + " = '" + Response.toString(newResponse) + "'");
    newResponse.ifPresentOrElse(
        r -> responses.put(field.getId(), r), () -> responses.remove(field.getId()));
    updateError(field, newResponse);
  }

  public void onFocusChange(Field field, boolean hasFocus) {
    if (!hasFocus) {
      updateError(field);
    }
  }

  public void onSaveClick() {
    saveClicks.onNext(Nil.NIL);
  }

  private Single<Form> onInitialize(EditObservationFragmentArgs viewArgs) {
    saveButtonVisibility.setValue(View.GONE);
    loadingSpinnerVisibility.setValue(View.VISIBLE);
    isNew = isAddObservationRequest(viewArgs);
    Single<Observation> obs;
    if (isNew) {
      toolbarTitle.setValue(resources.getString(R.string.add_observation_toolbar_title));
      obs = createObservation(viewArgs);
    } else {
      toolbarTitle.setValue(resources.getString(R.string.edit_observation_toolbar_title));
      obs = loadObservation(viewArgs);
    }
    return obs.doOnSuccess(this::onObservationLoaded).map(Observation::getForm);
  }

  private void onObservationLoaded(Observation observation) {
    this.originalObservation = observation;
    refreshResponseMap(observation);
    if (isNew) {
      validationErrors.clear();
    } else {
      refreshValidationErrors();
    }
    saveButtonVisibility.postValue(View.VISIBLE);
    loadingSpinnerVisibility.postValue(View.GONE);
  }

  private static boolean isAddObservationRequest(EditObservationFragmentArgs args) {
    return args.getRecordId().equals(ADD_OBSERVATION_ID_PLACEHOLDER);
  }

  private Single<Observation> createObservation(EditObservationFragmentArgs args) {
    return dataRepository
        .createObservation(args.getProjectId(), args.getFeatureId(), args.getFormId())
        .doOnError(
            t -> onError("Error creating new observation", RxJava2Debug.getEnhancedStackTrace(t)))
        .onErrorResumeNext(Single.never());
  }

  private Single<Observation> loadObservation(EditObservationFragmentArgs args) {
    return dataRepository
        .getObservation(args.getProjectId(), args.getFeatureId(), args.getRecordId())
        .doOnError(t -> onError("Error loading observation", RxJava2Debug.getEnhancedStackTrace(t)))
        .onErrorResumeNext(Single.never());
  }

  private Single<Event<SaveResult>> onSave() {
    if (originalObservation == null) {
      Log.e(TAG, "Save attempted before observation loaded");
      return Single.just(Event.of(SaveResult.NO_CHANGES_TO_SAVE));
    }
    refreshValidationErrors();
    if (hasValidationErrors()) {
      return Single.just(Event.of(SaveResult.HAS_VALIDATION_ERRORS));
    }
    if (!hasUnsavedChanges()) {
      return Single.just(Event.of(SaveResult.NO_CHANGES_TO_SAVE));
    }
    return save();
  }

  private void onError(String message, Throwable t) {
    // TODO: Refactor and stream to UI.
    Log.e(TAG, message, t);
  }

  private Single<Event<SaveResult>> save() {
    savingProgressVisibility.setValue(View.VISIBLE);
    AuthenticationManager.User currentUser =
        authManager.getUser().blockingFirst(AuthenticationManager.User.ANONYMOUS);
    ObservationMutation observationMutation =
        ObservationMutation.builder()
            .setType(isNew ? ObservationMutation.Type.CREATE : ObservationMutation.Type.UPDATE)
            .setProjectId(originalObservation.getProject().getId())
            .setFeatureId(originalObservation.getFeature().getId())
            .setLayerId(originalObservation.getFeature().getLayer().getId())
            .setRecordId(originalObservation.getId())
            .setFormId(originalObservation.getForm().getId())
            .setResponseDeltas(getResponseDeltas())
            .setUserId(currentUser.getId())
            .build();
    return dataRepository
        .applyAndEnqueue(observationMutation)
        .doOnComplete(() -> savingProgressVisibility.postValue(View.GONE))
        .toSingleDefault(Event.of(SaveResult.SAVED));
  }

  private void refreshResponseMap(Observation obs) {
    Log.v(TAG, "Rebuilding response map");
    responses.clear();
    ResponseMap responses = obs.getResponses();
    for (String fieldId : responses.fieldIds()) {
      obs.getForm()
          .getField(fieldId)
          .ifPresent(field -> onResponseChanged(field, responses.getResponse(fieldId)));
    }
  }

  private ImmutableList<ResponseDelta> getResponseDeltas() {
    if (originalObservation == null) {
      Log.e(TAG, "Response diff attempted before observation loaded");
      return ImmutableList.of();
    }
    ImmutableList.Builder<ResponseDelta> deltas = ImmutableList.builder();
    ResponseMap originalResponses = originalObservation.getResponses();
    Log.v(TAG, "Responses:\n Before: " + originalResponses + " \nAfter:  " + responses);
    for (Element e : originalObservation.getForm().getElements()) {
      if (e.getType() != Type.FIELD) {
        continue;
      }
      String fieldId = e.getField().getId();
      Optional<Response> originalResponse = originalResponses.getResponse(fieldId);
      Optional<Response> currentResponse = getResponse(fieldId).filter(r -> !r.isEmpty());
      if (currentResponse.equals(originalResponse)) {
        continue;
      }
      deltas.add(
          ResponseDelta.builder().setFieldId(fieldId).setNewResponse(currentResponse).build());
    }
    ImmutableList<ResponseDelta> result = deltas.build();
    Log.v(TAG, "Deltas: " + result);
    return result;
  }

  private void refreshValidationErrors() {
    validationErrors.clear();
    stream(originalObservation.getForm().getElements())
        .filter(e -> e.getType().equals(Type.FIELD))
        .map(e -> e.getField())
        .forEach(this::updateError);
  }

  private void updateError(Field field) {
    updateError(field, getResponse(field.getId()));
  }

  private void updateError(Field field, Optional<Response> response) {
    String key = field.getId();
    if (field.isRequired() && !response.filter(r -> !r.isEmpty()).isPresent()) {
      Log.d(TAG, "Missing: " + key);
      validationErrors.put(field.getId(), resources.getString(R.string.required_field));
    } else {
      Log.d(TAG, "Valid: " + key);
      validationErrors.remove(field.getId());
    }
  }

  public boolean hasUnsavedChanges() {
    return !getResponseDeltas().isEmpty();
  }

  private boolean hasValidationErrors() {
    return !validationErrors.isEmpty();
  }
}
