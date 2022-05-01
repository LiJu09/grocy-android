/*
 * This file is part of Grocy Android.
 *
 * Grocy Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grocy Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grocy Android. If not, see http://www.gnu.org/licenses/.
 *
 * Copyright (c) 2020-2022 by Patrick Zedler and Dominic Zedler
 */

package xyz.zedler.patrick.grocy.viewmodel;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import com.android.volley.VolleyError;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.api.GrocyApi;
import xyz.zedler.patrick.grocy.helper.DownloadHelper;
import xyz.zedler.patrick.grocy.model.Chore;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.model.InfoFullscreen;
import xyz.zedler.patrick.grocy.model.ShoppingList;
import xyz.zedler.patrick.grocy.model.User;
import xyz.zedler.patrick.grocy.repository.ShoppingListRepository;
import xyz.zedler.patrick.grocy.util.Constants;
import xyz.zedler.patrick.grocy.util.PrefsUtil;

public class ChoreEntryRescheduleViewModel extends BaseViewModel {

  private static final String TAG = ChoreEntryRescheduleViewModel.class.getSimpleName();

  private final SharedPreferences sharedPrefs;
  private final DownloadHelper dlHelper;
  private final GrocyApi grocyApi;
  private final EventHandler eventHandler;
  private final ShoppingListRepository repository;

  private final MutableLiveData<Boolean> isLoadingLive;
  private final MutableLiveData<InfoFullscreen> infoFullscreenLive;
  private final MutableLiveData<Boolean> offlineLive;

  private final MutableLiveData<String> nextTrackingDateLive;
  private final LiveData<String> nextTrackingTextLive;
  private final LiveData<String> nextTrackingHumanTextLive;
  private final MutableLiveData<String> nextTrackingTimeLive;
  private final MutableLiveData<User> userLive;

  private List<ShoppingList> shoppingLists;

  private DownloadHelper.Queue currentQueueLoading;
  private final Chore chore;
  private final boolean debug;

  public ChoreEntryRescheduleViewModel(@NonNull Application application, @Nullable Chore chore) {
    super(application);

    sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplication());
    debug = PrefsUtil.isDebuggingEnabled(sharedPrefs);

    isLoadingLive = new MutableLiveData<>(false);
    dlHelper = new DownloadHelper(getApplication(), TAG, isLoadingLive::setValue);
    grocyApi = new GrocyApi(getApplication());
    eventHandler = new EventHandler();
    repository = new ShoppingListRepository(application);

    infoFullscreenLive = new MutableLiveData<>();
    offlineLive = new MutableLiveData<>(false);

    nextTrackingDateLive = new MutableLiveData<>();
    nextTrackingTextLive = Transformations.map(
        nextTrackingDateLive,
        date -> date
    );
    nextTrackingHumanTextLive = Transformations.map(
        nextTrackingDateLive,
        date -> date
    );
    nextTrackingTimeLive = new MutableLiveData<>();
    userLive = new MutableLiveData<>();
    this.chore = chore;
  }

  public void loadFromDatabase(boolean downloadAfterLoading) {
    repository.loadShoppingListsFromDatabase(
        shoppingLists -> {
          this.shoppingLists = shoppingLists;
          if (downloadAfterLoading) {
            downloadData();
          }
        }
    );
  }

  public void downloadData(@Nullable String dbChangedTime) {
    if (currentQueueLoading != null) {
      currentQueueLoading.reset(true);
      currentQueueLoading = null;
    }
    if (isOffline()) { // skip downloading
      isLoadingLive.setValue(false);
      return;
    }
    if (dbChangedTime == null) {
      dlHelper.getTimeDbChanged(
          this::downloadData,
          () -> onDownloadError(null)
      );
      return;
    }

    DownloadHelper.Queue queue = dlHelper.newQueue(this::onQueueEmpty, this::onDownloadError);
    queue.append(dlHelper.updateShoppingLists(dbChangedTime, shoppingLists -> {
      this.shoppingLists = shoppingLists;
    }));

    if (queue.isEmpty()) {
      return;
    }

    currentQueueLoading = queue;
    queue.start();
  }

  public void downloadData() {
    downloadData(null);
  }

  public void downloadDataForceUpdate() {
    SharedPreferences.Editor editPrefs = sharedPrefs.edit();
    editPrefs.putString(Constants.PREF.DB_LAST_TIME_SHOPPING_LISTS, null);
    editPrefs.apply();
    downloadData();
  }

  private void onQueueEmpty() {
    if (isOffline()) {
      setOfflineLive(false);
    }
    repository.updateShoppingLists(this.shoppingLists);
  }

  private void onDownloadError(@Nullable VolleyError error) {
    if (debug) {
      Log.e(TAG, "onError: VolleyError: " + error);
    }
    showMessage(getString(R.string.msg_no_connection));
    if (!isOffline()) {
      setOfflineLive(true);
    }
  }

  public void saveShoppingList() {
    if (offlineLive.getValue()) {
      showMessage(getString(R.string.error_offline));
      return;
    }

    String name = null;
    JSONObject jsonObject = new JSONObject();
    try {
      jsonObject.put("name", name);
    } catch (JSONException e) {
      if (debug) {
        Log.e(TAG, "saveShoppingList: " + e);
      }
    }

    dlHelper.post(
        grocyApi.getObjects(GrocyApi.ENTITY.SHOPPING_LISTS),
        jsonObject,
        response -> {
          int objectId;
          try {
            objectId = response.getInt("created_object_id");
            Log.i(TAG, "saveShoppingList: " + objectId);
          } catch (JSONException e) {
            if (debug) {
              Log.e(TAG, "saveShoppingList: " + e);
            }
            objectId = 1;
          }
          sendEvent(Event.NAVIGATE_UP);
        },
        error -> {
          showErrorMessage();
          if (debug) {
            Log.e(TAG, "saveShoppingList: " + error);
          }
        }
    );
  }

  public void showNextTrackingDateBottomSheet() {

  }

  public void showNextTrackingTimeBottomSheet() {

  }

  public void showUsersBottomSheet() {

  }

  public void resetReschedule() {

  }

  public LiveData<String> getNextTrackingTextLive() {
    return nextTrackingTextLive;
  }

  public LiveData<String> getNextTrackingHumanTextLive() {
    return nextTrackingHumanTextLive;
  }

  public MutableLiveData<String> getNextTrackingTimeLive() {
    return nextTrackingTimeLive;
  }

  public MutableLiveData<User> getUserLive() {
    return userLive;
  }

  public Chore getChore() {
    return chore;
  }

  @NonNull
  public MutableLiveData<Boolean> getOfflineLive() {
    return offlineLive;
  }

  public Boolean isOffline() {
    return offlineLive.getValue();
  }

  public void setOfflineLive(boolean isOffline) {
    offlineLive.setValue(isOffline);
  }

  @NonNull
  public MutableLiveData<Boolean> getIsLoadingLive() {
    return isLoadingLive;
  }

  @NonNull
  public MutableLiveData<InfoFullscreen> getInfoFullscreenLive() {
    return infoFullscreenLive;
  }

  public void setCurrentQueueLoading(DownloadHelper.Queue queueLoading) {
    currentQueueLoading = queueLoading;
  }

  @NonNull
  public EventHandler getEventHandler() {
    return eventHandler;
  }

  @Override
  protected void onCleared() {
    dlHelper.destroy();
    super.onCleared();
  }

  public static class ChoreEntryRescheduleViewModelFactory implements ViewModelProvider.Factory {

    private final Application application;
    private final Chore chore;

    public ChoreEntryRescheduleViewModelFactory(Application application, Chore chore) {
      this.application = application;
      this.chore = chore;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      return (T) new ChoreEntryRescheduleViewModel(application, chore);
    }
  }
}