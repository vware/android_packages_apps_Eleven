/*
 * Copyright (C) 2014 The CyanogenMod Project
 * Copyright (C) 2019-2021 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.eleven.ui.activities;

import static org.lineageos.eleven.utils.PreferenceUtils.PERMISSION_REQUEST_STORAGE;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.app.ActionBar;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import org.lineageos.eleven.Config;
import org.lineageos.eleven.R;
import org.lineageos.eleven.cache.ImageFetcher;
import org.lineageos.eleven.ui.fragments.AlbumDetailFragment;
import org.lineageos.eleven.ui.fragments.ArtistDetailFragment;
import org.lineageos.eleven.ui.fragments.AudioPlayerFragment;
import org.lineageos.eleven.ui.fragments.ISetupActionBar;
import org.lineageos.eleven.ui.fragments.PlaylistDetailFragment;
import org.lineageos.eleven.ui.fragments.RecentFragment;
import org.lineageos.eleven.ui.fragments.phone.MusicBrowserPhoneFragment;
import org.lineageos.eleven.ui.fragments.profile.LastAddedFragment;
import org.lineageos.eleven.ui.fragments.profile.TopTracksFragment;
import org.lineageos.eleven.utils.AnimatorEndListener;
import org.lineageos.eleven.utils.ElevenUtils;
import org.lineageos.eleven.utils.MusicUtils;
import org.lineageos.eleven.utils.colors.BitmapWithColors;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class HomeActivity extends SlidingPanelActivity implements
        FragmentManager.OnBackStackChangedListener {
    private static final String TAG = "HomeActivity";
    private static final String ACTION_PREFIX = HomeActivity.class.getName();
    public static final String ACTION_VIEW_ARTIST_DETAILS = ACTION_PREFIX + ".view.ArtistDetails";
    public static final String ACTION_VIEW_ALBUM_DETAILS = ACTION_PREFIX + ".view.AlbumDetails";
    public static final String ACTION_VIEW_PLAYLIST_DETAILS =
            ACTION_PREFIX + ".view.PlaylistDetails";
    public static final String ACTION_VIEW_SMART_PLAYLIST = ACTION_PREFIX + ".view.SmartPlaylist";
    public static final String EXTRA_BROWSE_PAGE_IDX = "BrowsePageIndex";

    private static final String STATE_KEY_BASE_FRAGMENT = "BaseFragment";

    private static final int NEW_PHOTO = 1;
    public static final int EQUALIZER = 2;

    private Bundle mSavedInstanceState;

    private String mKey;
    private boolean mLoadedBaseFragment = false;
    private boolean mHasPendingPlaybackRequest = false;
    private final Handler mHandler = new Handler();
    private boolean mBrowsePanelActive = true;

    private View mRootView;

    /**
     * Used by the up action to determine how to handle this
     */
    protected boolean mTopLevelActivity = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSavedInstanceState = savedInstanceState;
        mRootView = getWindow().getDecorView();

        if (!needRequestStoragePermission()) {
            init(savedInstanceState);
        }
    }

    @Override
    protected void init(Bundle savedInstanceState) {
        super.init(savedInstanceState);

        // if we've been launched by an intent, parse it
        Intent launchIntent = getIntent();
        boolean intentHandled = false;
        if (launchIntent != null) {
            intentHandled = parseIntentForFragment(launchIntent);
        }

        // if the intent didn't cause us to load a fragment, load the music browse one
        if (savedInstanceState == null && !mLoadedBaseFragment) {
            final MusicBrowserPhoneFragment fragment = new MusicBrowserPhoneFragment();
            if (launchIntent != null) {
                fragment.setDefaultPageIdx(launchIntent.getIntExtra(EXTRA_BROWSE_PAGE_IDX,
                        MusicBrowserPhoneFragment.INVALID_PAGE_INDEX));
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.activity_base_content, fragment)
                    .commit();

            mLoadedBaseFragment = true;
            mTopLevelActivity = true;
        }

        getSupportFragmentManager().addOnBackStackChangedListener(this);

        // if we are resuming from a saved instance state
        if (savedInstanceState != null) {
            // track which fragments are loaded and if this is the top level activity
            mTopLevelActivity = savedInstanceState.getBoolean(STATE_KEY_BASE_FRAGMENT);
            mLoadedBaseFragment = mTopLevelActivity;

            // update the action bar based on the top most fragment
            onBackStackChanged();

            // figure which panel we are on and update the status bar
            mBrowsePanelActive = (getCurrentPanel() == Panel.Browse);
            updateStatusBarColor();
        }

        // if intent wasn't UI related, process it as a audio playback request
        if (!intentHandled) {
            handlePlaybackIntent(launchIntent);
        }

        mSavedInstanceState = null;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_KEY_BASE_FRAGMENT, mTopLevelActivity);
    }

    public Fragment getTopFragment() {
        return getSupportFragmentManager().findFragmentById(R.id.activity_base_content);
    }

    public void postRemoveFragment(final Fragment frag) {
        mHandler.post(() -> {
            // removing the fragment doesn't cause the back-stack event to be triggered even if
            // it is the top fragment, so if it is the top fragment, we will just manually
            // call pop back stack
            if (frag == getTopFragment()) {
                getSupportFragmentManager().popBackStack();
            } else {
                getSupportFragmentManager().beginTransaction().remove(frag).commit();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // parse intent to ascertain whether the intent is inter UI communication
        boolean intentHandled = parseIntentForFragment(intent);
        // since this activity is marked 'singleTop' (launch mode), an existing activity instance
        // could be sent media play requests
        if (!intentHandled) {
            handlePlaybackIntent(intent);
        }
    }

    @Override
    public void onMetaChanged() {
        super.onMetaChanged();
        updateStatusBarColor();
    }

    @Override
    protected void onSlide(float slideOffset) {
        boolean isInBrowser = getCurrentPanel() == Panel.Browse && slideOffset < 0.7f;
        if (isInBrowser != mBrowsePanelActive) {
            mBrowsePanelActive = isInBrowser;
            updateStatusBarColor();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (getCurrentPanel() == Panel.MusicPlayer) {
            getAudioPlayerFragment().setVisualizerVisible(hasFocus);
        }
    }

    private void updateStatusBarColor() {
        if (mBrowsePanelActive || MusicUtils.getCurrentAlbumId() < 0) {
            updateStatusBarColor(Color.TRANSPARENT);
        } else {
            Executor executor = Executors.newSingleThreadExecutor();
            Handler handler = new Handler(Looper.getMainLooper());

            executor.execute(() -> {
                ImageFetcher imageFetcher = ImageFetcher.getInstance(HomeActivity.this);
                BitmapWithColors bmc = imageFetcher.getArtwork(
                        MusicUtils.getAlbumName(), MusicUtils.getCurrentAlbumId(), true);

                handler.post(() -> {
                    updateVisualizerColor(bmc != null
                            ? bmc.getContrastingColor() : Color.TRANSPARENT);
                });
            });
        }
    }

    private void updateVisualizerColor(int color) {
        if (color == Color.TRANSPARENT) {
            color = ContextCompat.getColor(this, android.R.color.white);
        }

        // check for null since updatestatusBarColor is a async task
        AudioPlayerFragment fragment = getAudioPlayerFragment();
        if (fragment != null) {
            fragment.setVisualizerColor(color);
        }
    }

    private void updateStatusBarColor(int color) {
        if (color == Color.TRANSPARENT) {
            TypedValue c = new TypedValue();
            getTheme().resolveAttribute(R.attr.colorSurface, c, true);
            color = ContextCompat.getColor(this, c.resourceId);
        }
        final boolean isDark = ColorUtils.calculateLuminance(color) > 0.5f;
        final Window window = getWindow();
        final ObjectAnimator animator = ObjectAnimator.ofInt(window,
                "statusBarColor", window.getStatusBarColor(), color);
        animator.setEvaluator(new ArgbEvaluator());
        animator.setDuration(300);
        animator.addListener((AnimatorEndListener) animation -> {
            int flags = mRootView.getSystemUiVisibility();
            if (isDark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            mRootView.setSystemUiVisibility(flags);
        });
        animator.start();
    }

    private boolean parseIntentForFragment(Intent intent) {
        boolean handled = false;
        if (intent.getAction() != null) {
            final String action = intent.getAction();
            Fragment targetFragment = null;
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

            transaction.setCustomAnimations(
                    androidx.fragment.R.animator.fragment_open_enter,
                    androidx.fragment.R.animator.fragment_open_exit,
                    androidx.fragment.R.animator.fragment_fade_enter,
                    androidx.fragment.R.animator.fragment_fade_exit);

            if (action.equals(ACTION_VIEW_SMART_PLAYLIST)) {
                long playlistId = intent.getExtras().getLong(Config.SMART_PLAYLIST_TYPE);
                Config.SmartPlaylistType type = Config.SmartPlaylistType.getTypeById(playlistId);
                if (Config.SmartPlaylistType.LastAdded.equals(type)) {
                    targetFragment = new LastAddedFragment();
                } else if (Config.SmartPlaylistType.RecentlyPlayed.equals(type)) {
                    targetFragment = new RecentFragment();
                } else if (Config.SmartPlaylistType.TopTracks.equals(type)) {
                    targetFragment = new TopTracksFragment();
                }
            } else if (action.equals(ACTION_VIEW_PLAYLIST_DETAILS)) {
                targetFragment = new PlaylistDetailFragment();
            } else if (action.equals(ACTION_VIEW_ALBUM_DETAILS)) {
                targetFragment = new AlbumDetailFragment();
            } else if (action.equals(ACTION_VIEW_ARTIST_DETAILS)) {
                targetFragment = new ArtistDetailFragment();
            }

            if (targetFragment != null) {
                targetFragment.setArguments(intent.getExtras());
                // If we ever come back to this because of memory concerns because
                // none of the fragments are being removed from memory, we can fix this
                // by using "replace" instead of "add".  The caveat is that the performance of
                // returning to previous fragments is a bit more sluggish because the fragment
                // view needs to be recreated. If we do remove that, we can remove the back stack
                // change listener code above
                transaction.add(R.id.activity_base_content, targetFragment);
                if (mLoadedBaseFragment) {
                    transaction.addToBackStack(null);
                    showPanel(Panel.Browse);
                } else {
                    // else mark the fragment as loaded so we don't load the music browse fragment.
                    // this happens when they launch search which is its own activity and then
                    // browse through that back to home activity
                    mLoadedBaseFragment = true;
                    final ActionBar actionBar = getActionBar();
                    if (actionBar != null) {
                        actionBar.setDisplayHomeAsUpEnabled(true);
                    }
                }
                // the current top fragment is about to be hidden by what we are replacing
                // it with -- so tell that fragment not to make its action bar menu items visible
                Fragment oldTop = getTopFragment();
                if (oldTop != null) {
                    oldTop.setMenuVisibility(false);
                }

                transaction.commit();
                handled = true;
            }
        }
        return handled;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == NEW_PHOTO && !TextUtils.isEmpty(mKey)) {
            if (resultCode == RESULT_OK) {
                MusicUtils.removeFromCache(this, mKey);
                final Uri selectedImage = data.getData();

                new Thread(() -> {
                    Bitmap bitmap = ImageFetcher.decodeSampledBitmapFromUri(getContentResolver(),
                            selectedImage);
                    ImageFetcher imageFetcher = ElevenUtils.getImageFetcher(HomeActivity.this);
                    imageFetcher.addBitmapToCache(mKey, bitmap);
                    MusicUtils.refresh();
                }).start();
            }
        }
    }

    /**
     * Starts an activity for result that returns an image from the Gallery.
     */
    public void selectNewPhoto(String key) {
        mKey = key;
        // Now open the gallery
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT, null);
        intent.setType("image/*");
        startActivityForResult(intent, NEW_PHOTO);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void handlePendingPlaybackRequests() {
        if (mHasPendingPlaybackRequest) {
            Intent unhandledIntent = getIntent();
            handlePlaybackIntent(unhandledIntent);
        }
    }

    /**
     * Checks whether the passed intent contains a playback request,
     * and starts playback if that's the case
     *
     */
    private void handlePlaybackIntent(Intent intent) {
        if (intent == null) {
            return;
        } else if (!MusicUtils.isPlaybackServiceConnected()) {
            mHasPendingPlaybackRequest = true;
            return;
        }

        String mimeType = intent.getType();
        boolean handled = false;

        if (MediaStore.Audio.Playlists.CONTENT_TYPE.equals(mimeType)) {
            long id = parseIdFromIntent(intent, "playlistId", "playlist");
            if (id >= 0) {
                MusicUtils.playPlaylist(this, id, false);
                handled = true;
            }
        } else if (MediaStore.Audio.Albums.CONTENT_TYPE.equals(mimeType)) {
            long id = parseIdFromIntent(intent, "albumId", "album");
            if (id >= 0) {
                int position = intent.getIntExtra("position", 0);
                MusicUtils.playAlbum(this, id, position, false);
                handled = true;
            }
        } else if (MediaStore.Audio.Artists.CONTENT_TYPE.equals(mimeType)) {
            long id = parseIdFromIntent(intent, "artistId", "artist");
            if (id >= 0) {
                int position = intent.getIntExtra("position", 0);
                MusicUtils.playArtist(this, id, position, false);
                handled = true;
            }
        }

        // reset intent as it was handled as a playback request
        if (handled) {
            setIntent(new Intent());
        }

    }

    private long parseIdFromIntent(Intent intent, String longKey,
                                   String stringKey) {
        long id = intent.getLongExtra(longKey, -1);
        if (id < 0) {
            String idString = intent.getStringExtra(stringKey);
            if (idString != null) {
                try {
                    id = Long.parseLong(idString);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid id", e);
                }
            }
        }
        return id;
    }

    @Override
    public void onBackStackChanged() {
        Fragment topFragment = getTopFragment();
        if (topFragment != null) {
            // the fragment that has come back to the top should now have its menu items
            // added to the action bar -- so tell it to make it menu items visible
            topFragment.setMenuVisibility(true);
            ISetupActionBar setupActionBar = (ISetupActionBar) topFragment;
            setupActionBar.setupActionBar();

            final androidx.appcompat.app.ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(
                        !(topFragment instanceof MusicBrowserPhoneFragment));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_STORAGE) {
            if (checkPermissionGrantResults(grantResults)) {
                init(mSavedInstanceState);
            } else {
                finish();
            }
        }
    }

    private boolean needRequestStoragePermission() {
        boolean needRequest = false;
        String[] permissions = {
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
        };
        ArrayList<String> permissionList = new ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                permissionList.add(permission);
                needRequest = true;
            }
        }

        if (needRequest) {
            setRequestingPermissions(true);

            int count = permissionList.size();
            if (count > 0) {
                String[] permissionArray = new String[count];
                for (int i = 0; i < count; i++) {
                    permissionArray[i] = permissionList.get(i);
                }

                requestPermissions(permissionArray, PERMISSION_REQUEST_STORAGE);
            }
        }

        return needRequest;
    }

    private boolean checkPermissionGrantResults(int[] grantResults) {
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
