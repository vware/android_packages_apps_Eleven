/*
 * Copyright (C) 2012 Andrew Neal
 * Copyright (C) 2014-2016 The CyanogenMod Project
 * Copyright (C) 2018-2023 The LineageOS Project
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
package org.lineageos.eleven;

import android.Manifest.permission;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AlbumColumns;
import android.provider.MediaStore.Audio.AudioColumns;
import android.service.media.MediaBrowserService;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lineageos.eleven.Config.IdType;
import org.lineageos.eleven.appwidgets.AppWidgetLarge;
import org.lineageos.eleven.appwidgets.AppWidgetLargeAlternate;
import org.lineageos.eleven.appwidgets.AppWidgetSmall;
import org.lineageos.eleven.cache.ImageCache;
import org.lineageos.eleven.cache.ImageFetcher;
import org.lineageos.eleven.provider.MusicPlaybackState;
import org.lineageos.eleven.provider.RecentStore;
import org.lineageos.eleven.provider.SongPlayCount;
import org.lineageos.eleven.service.MusicPlaybackTrack;
import org.lineageos.eleven.utils.Lists;
import org.lineageos.eleven.utils.PreferenceUtils;
import org.lineageos.eleven.utils.ShakeDetector;
import org.lineageos.eleven.utils.SrtManager;
import org.lineageos.eleven.utils.colors.BitmapWithColors;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A background {@link Service} used to keep music playing between activities
 * and when the user moves Eleven into the background.
 */
public class MusicPlaybackService extends MediaBrowserService
        implements AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "MusicPlaybackService";
    private static final boolean D = false;

    private static final String PKG_NAME = BuildConstants.PACKAGE_NAME;

    /**
     * Indicates that the music has paused or resumed
     */
    public static final String PLAYSTATE_CHANGED = PKG_NAME + ".playstatechanged";

    /**
     * Indicates that music playback position within
     * a title was changed
     */
    public static final String POSITION_CHANGED = PKG_NAME + ".positionchanged";

    /**
     * Indicates the meta data has changed in some way, like a track change
     */
    public static final String META_CHANGED = PKG_NAME + ".metachanged";

    /**
     * Indicates the queue has been updated
     */
    public static final String QUEUE_CHANGED = PKG_NAME + ".queuechanged";

    /**
     * Indicates a queue item has been moved
     */
    public static final String QUEUE_MOVED = PKG_NAME + ".queuemoved";

    /**
     * Indicates the queue has been updated
     */
    public static final String PLAYLIST_CHANGED = PKG_NAME + ".playlistchanged";

    /**
     * Indicates the repeat mode changed
     */
    public static final String REPEATMODE_CHANGED = PKG_NAME + ".repeatmodechanged";

    /**
     * Indicates the shuffle mode changed
     */
    public static final String SHUFFLEMODE_CHANGED = PKG_NAME + ".shufflemodechanged";

    /**
     * Indicates the track fails to play
     */
    public static final String TRACK_ERROR = PKG_NAME + ".trackerror";

    /**
     * For backwards compatibility reasons, also provide sticky
     * broadcasts under the music package
     */
    public static final String ELEVEN_PACKAGE_NAME = PKG_NAME;
    public static final String MUSIC_PACKAGE_NAME = "com.android.music";

    /**
     * Called to indicate a general service commmand. Used in
     * {@link MediaButtonIntentReceiver}
     */
    public static final String SERVICECMD = PKG_NAME + ".musicservicecommand";

    /**
     * Called to go toggle between pausing and playing the music
     */
    public static final String TOGGLEPAUSE_ACTION = PKG_NAME + ".togglepause";

    /**
     * Called to go to pause the playback
     */
    public static final String PAUSE_ACTION = PKG_NAME + ".pause";

    /**
     * Called to go to stop the playback
     */
    public static final String STOP_ACTION = PKG_NAME + ".stop";

    /**
     * Called to go to the previous track or the beginning of the track if partway through the track
     */
    public static final String PREVIOUS_ACTION = PKG_NAME + ".previous";

    /**
     * Called to go to the previous track regardless of how far in the current track the playback is
     */
    public static final String PREVIOUS_FORCE_ACTION = PKG_NAME + ".previous.force";

    /**
     * Called to go to the next track
     */
    public static final String NEXT_ACTION = PKG_NAME + ".next";

    /**
     * Called to change the repeat mode
     */
    public static final String REPEAT_ACTION = PKG_NAME + ".repeat";

    /**
     * Called to change the shuffle mode
     */
    public static final String SHUFFLE_ACTION = PKG_NAME + ".shuffle";

    public static final String FROM_MEDIA_BUTTON = "frommediabutton";

    public static final String TIMESTAMP = "timestamp";

    /**
     * Used to easily notify a list that it should refresh. i.e. A playlist
     * changes
     */
    public static final String REFRESH = PKG_NAME + ".refresh";

    /**
     * Used by the alarm intent to shutdown the service after being idle
     */
    private static final String SHUTDOWN = PKG_NAME + ".shutdown";

    /**
     * Called to notify of a timed text
     */
    public static final String NEW_LYRICS = PKG_NAME + ".lyrics";

    public static final String CMDNAME = "command";

    public static final String CMDTOGGLEPAUSE = "togglepause";

    public static final String CMDSTOP = "stop";

    public static final String CMDPAUSE = "pause";

    public static final String CMDPLAY = "play";

    public static final String CMDPREVIOUS = "previous";

    public static final String CMDNEXT = "next";

    public static final String CMDHEADSETHOOK = "headsethook";

    private static final int IDCOLIDX = 0;

    /**
     * Moves a list to the next position in the queue
     */
    public static final int NEXT = 2;

    /**
     * Moves a list to the last position in the queue
     */
    public static final int LAST = 3;

    /**
     * Shuffles no songs, turns shuffling off
     */
    public static final int SHUFFLE_NONE = 0;

    /**
     * Shuffles all songs
     */
    public static final int SHUFFLE_NORMAL = 1;

    /**
     * Party shuffle
     */
    public static final int SHUFFLE_AUTO = 2;

    /**
     * Turns repeat off
     */
    public static final int REPEAT_NONE = 0;

    /**
     * Repeats the current track in a list
     */
    public static final int REPEAT_CURRENT = 1;

    /**
     * Repeats all the tracks in a list
     */
    public static final int REPEAT_ALL = 2;

    /**
     * Indicates when the track ends
     */
    private static final int TRACK_ENDED = 1;

    /**
     * Indicates that the current track was changed the next track
     */
    private static final int TRACK_WENT_TO_NEXT = 2;

    /**
     * Indicates the player died
     */
    private static final int SERVER_DIED = 3;

    /**
     * Indicates to fade the volume down
     */
    private static final int FADEDOWN = 5;

    /**
     * Indicates to fade the volume back up
     */
    private static final int FADEUP = 6;

    /**
     * Notifies that there is a new timed text string
     */
    private static final int LYRICS = 7;

    /**
     * Indicates a headset hook key event
     */
    private static final int HEADSET_HOOK_EVENT = 8;

    /**
     * Indicates waiting for another headset hook event has timed out
     */
    private static final int HEADSET_HOOK_MULTI_CLICK_TIMEOUT = 9;

    /**
     * Idle time before stopping the foreground notfication (5 minutes)
     */
    private static final int IDLE_DELAY = 5 * 60 * 1000;

    /**
     * Song play time used as threshold for rewinding to the beginning of the
     * track instead of skipping to the previous track when getting the PREVIOUS
     * command
     */
    private static final long REWIND_INSTEAD_PREVIOUS_THRESHOLD = 3000;

    /**
     * The max size allowed for the track history
     * TODO: Comeback and rewrite/fix all the whole queue code bugs after demo
     */
    public static final int MAX_HISTORY_SIZE = 1000;

    private static final String ACTION_AUDIO_PLAYER = BuildConstants.PACKAGE_NAME + ".AUDIO_PLAYER";

    private static final String CHANNEL_NAME = "eleven_playback";

    private static final String CONTENT_STYLE_BROWSABLE_HINT =
            "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";

    private static final String CONTENT_STYLE_PLAYABLE_HINT =
            "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";

    private static final String CONTENT_BROWSER_ROOT = "root";

    private static final String CONTENT_BROWSER_ALBUMS = "eleven_albums";

    private static final String CONTENT_BROWSER_ARTISTS = "eleven_artists";

    private static final String CONTENT_BROWSER_PLAYLISTS = "eleven_playlists";

    private static final String CONTENT_BROWSER_SONGS = "eleven_songs";

    private final int CONTENT_STYLE_GRID_ITEM = 2;

    private final int CONTENT_STYLE_LIST_ITEM = 1;

    public interface TrackErrorExtra {
        /**
         * Name of the track that was unable to play
         */
        String TRACK_NAME = "trackname";
    }

    /**
     * The columns used to retrieve any info from the current track
     */
    private static final String[] PROJECTION = new String[]{
            BaseColumns._ID, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID
    };

    /**
     * The columns used to retrieve any info from the current album
     */
    private static final String[] ALBUM_PROJECTION = new String[]{
            MediaStore.Audio.Albums.ALBUM, MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.LAST_YEAR
    };

    /**
     * Keeps a mapping of the track history
     */
    private static LinkedList<Integer> mHistory = Lists.newLinkedList();

    /**
     * Used to shuffle the tracks
     */
    private static final Shuffler mShuffler = new Shuffler();

    /**
     * Service stub
     */
    private final IBinder mBinder = new ServiceStub(this);

    /**
     * Square widget
     */
    private final AppWidgetSmall mAppWidgetSmall = AppWidgetSmall.getInstance();

    /**
     * 4x2 widget
     */
    private final AppWidgetLarge mAppWidgetLarge = AppWidgetLarge.getInstance();

    /**
     * 4x2 alternate widget
     */
    private final AppWidgetLargeAlternate mAppWidgetLargeAlternate = AppWidgetLargeAlternate
            .getInstance();

    /**
     * The media player
     */
    private MultiPlayer mPlayer;

    /**
     * The path of the current file to play
     */
    private String mFileToPlay;

    /**
     * Alarm intent for removing the notification when nothing is playing
     * for some time
     */
    private AlarmManager mAlarmManager;
    private PendingIntent mShutdownIntent;
    private boolean mShutdownScheduled;

    private NotificationManager mNotificationManager;

    /**
     * The cursor used to retrieve info on the current track and run the
     * necessary queries to play audio files
     */
    private Cursor mCursor;

    /**
     * The cursor used to retrieve info on the album the current track is
     * part of, if any.
     */
    private Cursor mAlbumCursor;

    /**
     * Monitors the audio state
     */
    private AudioManager mAudioManager;

    private AudioAttributes mAudioAttributes;
    private AudioFocusRequest mAudioFocusRequest;

    /**
     * Settings used to save and retrieve the queue and history
     */
    private SharedPreferences mPreferences;

    /**
     * Used to know when the service is active
     */
    private boolean mIsBound = false;

    /**
     * Used to know if something should be playing or not
     */
    private boolean mIsSupposedToBePlaying = false;

    /**
     * Gets the last played time to determine whether we still want notifications or not
     */
    private long mLastPlayedTime;

    private int mNotifyMode = NOTIFY_MODE_NONE;
    private long mNotificationPostTime = 0;

    private static final int NOTIFICATION_ID = 0x1337;

    private static final int NOTIFY_MODE_NONE = 0;
    private static final int NOTIFY_MODE_FOREGROUND = 1;
    private static final int NOTIFY_MODE_BACKGROUND = 2;

    /**
     * Used to indicate if the queue can be saved
     */
    private boolean mQueueIsSaveable = true;

    /**
     * Used to track what type of audio focus loss caused the playback to pause
     */
    private boolean mPausedByTransientLossOfFocus = false;

    /**
     * Lock screen controls
     */
    private MediaSession mSession;

    // We use this to distinguish between different cards when saving/restoring
    // playlists
    private int mCardId;

    private int mPlayPos = -1;

    private int mNextPlayPos = -1;

    private int mOpenFailedCounter = 0;

    private int mMediaMountedCount = 0;

    private int mShuffleMode = SHUFFLE_NONE;

    private int mRepeatMode = REPEAT_NONE;

    private int mServiceStartId = -1;

    private String mLyrics;

    private ArrayList<MusicPlaybackTrack> mPlaylist = new ArrayList<>(100);

    private long[] mAutoShuffleList = null;

    private MusicPlayerHandler mPlayerHandler;
    private HandlerThread mHandlerThread;

    private BroadcastReceiver mUnmountReceiver = null;

    // to improve perf, instead of hitting the disk cache or file cache, store the bitmaps in memory
    private String mCachedKey;
    private final BitmapWithColors[] mCachedBitmapWithColors = new BitmapWithColors[2];

    private QueueUpdateTask mQueueUpdateTask;

    /**
     * Image cache
     */
    private ImageFetcher mImageFetcher;

    /**
     * Recently listened database
     */
    private RecentStore mRecentsCache;

    /**
     * The song play count database
     */
    private SongPlayCount mSongPlayCountCache;

    /**
     * Stores the playback state
     */
    private MusicPlaybackState mPlaybackStateStore;

    /**
     * Shake detector class used for shake to switch song feature
     */
    private ShakeDetector mShakeDetector;

    private boolean mReadGranted = false;

    private PowerManager.WakeLock mHeadsetHookWakeLock;

    /**
     * Resource URI for the application
     */
    private final String mResourceUri = "android.resource://" + PKG_NAME + "/drawable/";

    /**
     * Only used for Android Auto, as it can only access this service
     */
    private HashMap<String, List<MediaBrowser.MediaItem>> mMediaIdToChildren = new HashMap<>();
    private ArrayList<Long> mSongs = new ArrayList<>(100);

    @Override
    public IBinder onBind(final Intent intent) {
        if (D) Log.d(TAG, "Service bound, intent = " + intent);
        if (MediaBrowserService.SERVICE_INTERFACE.equals(intent.getAction())) {
            return super.onBind(intent);
        }
        mIsBound = true;
        return mBinder;
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid,
                                 @Nullable Bundle bundle) {
        Bundle extras = new Bundle();
        extras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM);
        extras.putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM);
        return new BrowserRoot(CONTENT_BROWSER_ROOT, extras);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                               @NonNull Result<List<MediaBrowser.MediaItem>> result) {
        List<MediaBrowser.MediaItem> mediaItems = new ArrayList<>();
        switch (parentId) {
            case CONTENT_BROWSER_ROOT:
                result.sendResult(mMediaIdToChildren.get(CONTENT_BROWSER_ROOT));
                return;
            case CONTENT_BROWSER_ALBUMS:
                result.sendResult(setupAlbumRoot());
                return;
            case CONTENT_BROWSER_ARTISTS:
                result.sendResult(setupArtistRoot());
                return;
            case CONTENT_BROWSER_PLAYLISTS:
                // TODO: Build playlist root
                break;
            default:
                result.sendResult(mMediaIdToChildren.get(parentId));
                return;
        }
        result.sendResult(mediaItems);
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        if (D) Log.d(TAG, "Service unbound");
        mIsBound = false;
        saveQueue(true);

        if (mReadGranted) {
            if (mIsSupposedToBePlaying || mPausedByTransientLossOfFocus) {
                // Something is currently playing, or will be playing once
                // an in-progress action requesting audio focus ends, so don't stop
                // the service now.
                return true;

                // If there is a playlist but playback is paused, then wait a while
                // before stopping the service, so that pause/resume isn't slow.
                // Also delay stopping the service if we're transitioning between
                // tracks.
            } else if (mPlaylist.size() > 0 || mPlayerHandler.hasMessages(TRACK_ENDED)) {
                return true;
            }
        }

        return true;
    }

    @Override
    public void onRebind(final Intent intent) {
        mIsBound = true;
    }

    @Override
    public void onCreate() {
        if (D) Log.d(TAG, "Creating service");
        super.onCreate();

        if (checkSelfPermission(permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        } else {
            mReadGranted = true;
        }

        mNotificationManager = getSystemService(NotificationManager.class);

        // Initialize the favorites and recents databases
        mRecentsCache = RecentStore.getInstance(this);

        // gets the song play count cache
        mSongPlayCountCache = SongPlayCount.getInstance(this);

        // gets a pointer to the playback state store
        mPlaybackStateStore = MusicPlaybackState.getInstance(this);

        // Initialize the image fetcher
        mImageFetcher = ImageFetcher.getInstance(this);
        // Initialize the image cache
        mImageFetcher.setImageCache(ImageCache.getInstance(this));

        // Start up the thread running the service. Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block. We also make it
        // background priority so CPU-intensive work will not disrupt the UI.
        mHandlerThread = new HandlerThread("MusicPlayerHandler",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();

        // Initialize the handler
        mPlayerHandler = new MusicPlayerHandler(this, mHandlerThread.getLooper());

        // Initialize the audio manager and register any headset controls for
        // playback
        mAudioManager = getSystemService(AudioManager.class);

        // Use the remote control APIs to set the playback state
        setUpMediaSession();

        // Initialize the preferences
        mPreferences = getSharedPreferences("Service", 0);
        mCardId = getCardId();

        setShakeToPlayEnabled(PreferenceUtils.getInstance(this).getShakeToPlay());

        mRepeatMode = mPreferences.getInt("repeatmode", REPEAT_NONE);
        mShuffleMode = mPreferences.getInt("shufflemode", SHUFFLE_NONE);

        registerExternalStorageListener();

        // Initialize the media player
        mPlayer = new MultiPlayer(this, mPlayerHandler, mAudioAttributes);

        // Initialize the intent filter and each action
        final IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICECMD);
        filter.addAction(TOGGLEPAUSE_ACTION);
        filter.addAction(PAUSE_ACTION);
        filter.addAction(STOP_ACTION);
        filter.addAction(NEXT_ACTION);
        filter.addAction(PREVIOUS_ACTION);
        filter.addAction(PREVIOUS_FORCE_ACTION);
        filter.addAction(REPEAT_ACTION);
        filter.addAction(SHUFFLE_ACTION);
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        // Attach the broadcast listener
        registerReceiver(mIntentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        // Get events when MediaStore content changes
        mMediaStoreObserver = new MediaStoreObserver(mPlayerHandler);
        getContentResolver().registerContentObserver(
                MediaStore.Audio.Media.INTERNAL_CONTENT_URI, true, mMediaStoreObserver);
        getContentResolver().registerContentObserver(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true,
                mMediaStoreObserver);

        // Initialize the delayed shutdown intent
        final Intent shutdownIntent = new Intent(this, MusicPlaybackService.class);
        shutdownIntent.setAction(SHUTDOWN);

        mAlarmManager = getSystemService(AlarmManager.class);
        mShutdownIntent = PendingIntent.getService(this, 0, shutdownIntent,
                PendingIntent.FLAG_IMMUTABLE);

        // Bring the queue back
        reloadQueue();
        notifyChange(QUEUE_CHANGED);
        notifyChange(META_CHANGED);

        // Initialize the media tree. Only used for Android Auto
        setupRootMediaItems();
        setupSongRoot();
    }

    private void setUpMediaSession() {
        mAudioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();
        mAudioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mAudioAttributes)
                .setOnAudioFocusChangeListener(this, mPlayerHandler)
                .build();

        mSession = new MediaSession(this, "Eleven");
        mSession.setPlaybackToLocal(mAudioAttributes);
        mSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPause() {
                pause(false);
            }

            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onSeekTo(long pos) {
                seek(pos);
            }

            @Override
            public void onSkipToNext() {
                gotoNext(true);
            }

            @Override
            public void onSkipToPrevious() {
                prev(false);
            }

            @Override
            public void onStop() {
                pause(false);
                seek(0);
                releaseServiceUiAndStop();
            }

            @Override
            public void onSkipToQueueItem(long id) {
                setQueuePosition((int) id);
            }

            @Override
            public boolean onMediaButtonEvent(@NonNull Intent mediaButtonIntent) {
                if (Intent.ACTION_MEDIA_BUTTON.equals(mediaButtonIntent.getAction())) {
                    KeyEvent ke = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (ke != null && ke.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK) {
                        if (ke.getAction() == KeyEvent.ACTION_UP) {
                            handleHeadsetHookClick(ke.getEventTime());
                        }
                        return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }

            @Override
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                int position = mSongs.indexOf(Long.parseLong(mediaId));
                // TODO: Detect when played from playlist, album, or artist
                open(mSongs.stream().mapToLong(i -> i).toArray(), position, -1, IdType.NA);
                onPlay();
            }

            @Override
            public void onCustomAction(@NonNull String action, @Nullable Bundle extras) {
                if (action.equals(SHUFFLE_ACTION)) {
                    cycleShuffle();
                }
                else if (action.equals(REPEAT_ACTION)) {
                    cycleRepeat();
                }
            }
        });

        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(this, MediaButtonIntentReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mSession.setMediaButtonReceiver(pi);
        setSessionToken(mSession.getSessionToken());
    }

    @Override
    public void onDestroy() {
        if (D) Log.d(TAG, "Destroying service");
        if (!mReadGranted) {
            return;
        }
        super.onDestroy();
        // Remove any sound effects
        final Intent audioEffectsIntent = new Intent(
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        audioEffectsIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
        audioEffectsIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        sendBroadcast(audioEffectsIntent);

        // remove any pending alarms
        mAlarmManager.cancel(mShutdownIntent);

        // Remove any callbacks from the handler
        mPlayerHandler.removeCallbacksAndMessages(null);
        // quit the thread so that anything that gets posted won't run
        mHandlerThread.quitSafely();

        // Release the player
        mPlayer.release();
        mPlayer = null;

        // Remove the audio focus listener and lock screen controls
        mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
        mSession.release();

        // remove the media store observer
        getContentResolver().unregisterContentObserver(mMediaStoreObserver);

        // Close the cursor
        closeCursor();

        // Unregister the mount listener
        unregisterReceiver(mIntentReceiver);
        if (mUnmountReceiver != null) {
            unregisterReceiver(mUnmountReceiver);
            mUnmountReceiver = null;
        }

        // deinitialize shake detector
        stopShakeDetector(true);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (D) Log.d(TAG, "Got new intent " + intent + ", startId = " + startId);
        mServiceStartId = startId;

        if (intent != null) {
            final String action = intent.getAction();

            if (SHUTDOWN.equals(action)) {
                mShutdownScheduled = false;
                releaseServiceUiAndStop();
            }

            handleCommandIntent(intent);
        }

        if (intent != null && intent.getBooleanExtra(FROM_MEDIA_BUTTON, false)) {
            MediaButtonIntentReceiver.completeWakefulIntent(intent);
        }

        // Make sure the service will shut down on its own if it was
        // just started but not bound to and nothing is playing
        if (mIsSupposedToBePlaying || mPausedByTransientLossOfFocus) {
            cancelShutdown();
            return START_STICKY;
        } else {
            scheduleDelayedShutdown();
            return START_NOT_STICKY;
        }
    }

    private void releaseServiceUiAndStop() {
        if (isPlaying()
                || mPausedByTransientLossOfFocus
                || mPlayerHandler.hasMessages(TRACK_ENDED)) {
            return;
        }

        if (D) Log.d(TAG, "Nothing is playing anymore, releasing notification");
        cancelNotification();
        mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
        mSession.setActive(false);

        if (!mIsBound) {
            saveQueue(true);
        }
        stopSelf(mServiceStartId);
    }

    private void handleCommandIntent(Intent intent) {
        final String action = intent.getAction();
        final String command = SERVICECMD.equals(action) ? intent.getStringExtra(CMDNAME) : null;

        if (D) Log.d(TAG, "handleCommandIntent: action = " + action + ", command = " + command);

        if (CMDNEXT.equals(command) || NEXT_ACTION.equals(action)) {
            gotoNext(true);
        } else if (CMDPREVIOUS.equals(command) || PREVIOUS_ACTION.equals(action)
                || PREVIOUS_FORCE_ACTION.equals(action)) {
            prev(PREVIOUS_FORCE_ACTION.equals(action));
        } else if (CMDTOGGLEPAUSE.equals(command) || TOGGLEPAUSE_ACTION.equals(action)) {
            togglePlayPause();
        } else if (CMDPAUSE.equals(command) || PAUSE_ACTION.equals(action)
                || AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(action)) {
            pause(false);
        } else if (CMDPLAY.equals(command)) {
            play();
        } else if (CMDSTOP.equals(command) || STOP_ACTION.equals(action)) {
            pause(false);
            seek(0);
            releaseServiceUiAndStop();
        } else if (REPEAT_ACTION.equals(action)) {
            cycleRepeat();
        } else if (SHUFFLE_ACTION.equals(action)) {
            cycleShuffle();
        } else if (CMDHEADSETHOOK.equals(command)) {
            long timestamp = intent.getLongExtra(TIMESTAMP, 0);
            handleHeadsetHookClick(timestamp);
        }
    }

    private void handleHeadsetHookClick(long timestamp) {
        if (mHeadsetHookWakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            mHeadsetHookWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "eleven:headsethook");
            mHeadsetHookWakeLock.setReferenceCounted(false);
        }
        // Make sure we don't indefinitely hold the wake lock under any circumstances
        mHeadsetHookWakeLock.acquire(10000);

        Message msg = mPlayerHandler.obtainMessage(HEADSET_HOOK_EVENT, timestamp);
        msg.sendToTarget();
    }

    /**
     * Updates the notification, considering the current play and activity state
     */
    private void updateNotification() {
        final int newNotifyMode;
        if (mIsSupposedToBePlaying || mPausedByTransientLossOfFocus) {
            newNotifyMode = NOTIFY_MODE_FOREGROUND;
        } else if (recentlyPlayed()) {
            newNotifyMode = NOTIFY_MODE_BACKGROUND;
        } else {
            newNotifyMode = NOTIFY_MODE_NONE;
        }

        if (mNotifyMode != newNotifyMode) {
            if (mNotifyMode == NOTIFY_MODE_FOREGROUND) {
                stopForeground(newNotifyMode == NOTIFY_MODE_NONE);
            } else if (newNotifyMode == NOTIFY_MODE_NONE) {
                mNotificationManager.cancel(NOTIFICATION_ID);
                mNotificationPostTime = 0;
            }
        }

        if (newNotifyMode == NOTIFY_MODE_FOREGROUND) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else if (newNotifyMode == NOTIFY_MODE_BACKGROUND) {
            mNotificationManager.notify(NOTIFICATION_ID, buildNotification());
        }

        mNotifyMode = newNotifyMode;
    }

    private void cancelNotification() {
        stopForeground(true);
        mNotificationManager.cancel(NOTIFICATION_ID);
        mNotificationPostTime = 0;
        mNotifyMode = NOTIFY_MODE_NONE;
    }

    /**
     * @return A card ID used to save and restore playlists, i.e., the queue.
     */
    private int getCardId() {
        final ContentResolver resolver = getContentResolver();
        Cursor cursor = resolver.query(Uri.parse("content://media/external/fs_id"), null, null,
                null, null);
        int mCardId = -1;
        if (cursor != null && cursor.moveToFirst()) {
            mCardId = cursor.getInt(0);
            cursor.close();
        }
        return mCardId;
    }

    /**
     * Called when we receive a ACTION_MEDIA_EJECT notification.
     */
    public void closeExternalStorageFiles() {
        stop(true);
        notifyChange(QUEUE_CHANGED);
        notifyChange(META_CHANGED);
    }

    /**
     * Registers an intent to listen for ACTION_MEDIA_EJECT notifications. The
     * intent will call closeExternalStorageFiles() if the external media is
     * going to be ejected, so applications can clean up any files they have
     * open.
     */
    public void registerExternalStorageListener() {
        if (mUnmountReceiver == null) {
            mUnmountReceiver = new BroadcastReceiver() {

                @Override
                public void onReceive(final Context context, final Intent intent) {
                    final String action = intent.getAction();
                    if (Intent.ACTION_MEDIA_EJECT.equals(action)) {
                        saveQueue(true);
                        mQueueIsSaveable = false;
                        closeExternalStorageFiles();
                    } else if (action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
                        mMediaMountedCount++;
                        mCardId = getCardId();
                        reloadQueue();
                        mQueueIsSaveable = true;
                        notifyChange(QUEUE_CHANGED);
                        notifyChange(META_CHANGED);
                    }
                }
            };
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_EJECT);
            filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            filter.addDataScheme("file");
            registerReceiver(mUnmountReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
    }

    private void scheduleDelayedShutdown() {
        if (D) Log.v(TAG, "Scheduling shutdown in " + IDLE_DELAY + " ms");
        if (!mReadGranted) {
            return;
        }
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + IDLE_DELAY, mShutdownIntent);
        mShutdownScheduled = true;
    }

    private void cancelShutdown() {
        if (D) Log.d(TAG, "Cancelling delayed shutdown, scheduled = " + mShutdownScheduled);
        if (mShutdownScheduled) {
            mAlarmManager.cancel(mShutdownIntent);
            mShutdownScheduled = false;
        }
    }

    /**
     * Stops playback
     *
     * @param goToIdle True to go to the idle state, false otherwise
     */
    private void stop(final boolean goToIdle) {
        if (D) Log.d(TAG, "Stopping playback, goToIdle = " + goToIdle);
        if (mPlayer.isInitialized()) {
            mPlayer.stop();
        }
        mFileToPlay = null;
        closeCursor();
        if (goToIdle) {
            setIsSupposedToBePlaying(false, false);
        } else {
            stopForeground(false);
        }
    }

    /**
     * Removes the range of tracks specified from the play list. If a file
     * within the range is the file currently being played, playback will move
     * to the next file after the range.
     *
     * @param first The first file to be removed
     * @param last  The last file to be removed
     * @return the number of tracks deleted
     */
    private int removeTracksInternal(int first, int last) {
        synchronized (this) {
            if (last < first) {
                return 0;
            } else if (first < 0) {
                first = 0;
            } else if (last >= mPlaylist.size()) {
                last = mPlaylist.size() - 1;
            }

            boolean gotonext = false;
            if (first <= mPlayPos && mPlayPos <= last) {
                mPlayPos = first;
                gotonext = true;
            } else if (mPlayPos > last) {
                mPlayPos -= last - first + 1;
            }
            final int numToRemove = last - first + 1;

            if (first == 0 && last == mPlaylist.size() - 1) {
                mPlayPos = -1;
                mNextPlayPos = -1;
                mPlaylist.clear();
                mHistory.clear();
            } else {
                for (int i = 0; i < numToRemove; i++) {
                    mPlaylist.remove(first);
                }

                // remove the items from the history
                // this is not ideal as the history shouldn't be impacted by this
                // but since we are removing items from the array, it will throw
                // an exception if we keep it around.
                ListIterator<Integer> positionIterator = mHistory.listIterator();
                while (positionIterator.hasNext()) {
                    int pos = positionIterator.next();
                    if (pos >= first && pos <= last) {
                        positionIterator.remove();
                    } else if (pos > last) {
                        positionIterator.set(pos - numToRemove);
                    }
                }
            }
            if (gotonext) {
                if (mPlaylist.size() == 0) {
                    stop(true);
                    mPlayPos = -1;
                    closeCursor();
                } else {
                    if (mShuffleMode != SHUFFLE_NONE) {
                        mPlayPos = getNextPosition(true);
                    } else if (mPlayPos >= mPlaylist.size()) {
                        mPlayPos = 0;
                    }
                    final boolean wasPlaying = isPlaying();
                    stop(false);
                    openCurrentAndNext();
                    if (wasPlaying) {
                        play();
                    }
                }
                notifyChange(META_CHANGED);
            }
            return last - first + 1;
        }
    }

    /**
     * Adds a list to the playlist
     *
     * @param list     The list to add
     * @param position The position to place the tracks
     */
    private void addToPlayList(final long[] list, int position, long sourceId, IdType sourceType) {
        final int addlen = list.length;
        if (position < 0) {
            mPlaylist.clear();
            position = 0;
        }

        mPlaylist.ensureCapacity(mPlaylist.size() + addlen);
        if (position > mPlaylist.size()) {
            position = mPlaylist.size();
        }

        final ArrayList<MusicPlaybackTrack> arrayList = new ArrayList<>(addlen);
        for (int i = 0; i < list.length; i++) {
            arrayList.add(new MusicPlaybackTrack(list[i], sourceId, sourceType, i));
        }

        mPlaylist.addAll(position, arrayList);

        if (mPlaylist.size() == 0) {
            closeCursor();
            notifyChange(META_CHANGED);
        }
        notifyChange(QUEUE_CHANGED);
    }

    /**
     * @param trackId The track ID
     */
    private void updateCursor(final long trackId) {
        updateCursor("_id=" + trackId, null);
    }

    private void updateCursor(final String selection, final String[] selectionArgs) {
        synchronized (this) {
            closeCursor();
            mCursor = openCursorAndGoToFirst(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    PROJECTION, selection, selectionArgs);
        }
        updateAlbumCursor();
    }

    private void updateCursor(final Uri uri) {
        synchronized (this) {
            closeCursor();
            mCursor = openCursorAndGoToFirst(uri, PROJECTION, null, null);
        }
        updateAlbumCursor();
    }

    private void updateAlbumCursor() {
        long albumId = getAlbumId();
        if (albumId >= 0) {
            mAlbumCursor = openCursorAndGoToFirst(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    ALBUM_PROJECTION, "_id=" + albumId, null);
        } else {
            mAlbumCursor = null;
        }
    }

    private Cursor openCursorAndGoToFirst(Uri uri, String[] projection,
                                          String selection, String[] selectionArgs) {
        Cursor c = getContentResolver().query(uri, projection,
                selection, selectionArgs, null, null);
        if (c == null) {
            return null;
        }
        if (!c.moveToFirst()) {
            c.close();
            return null;
        }
        return c;
    }

    private synchronized void closeCursor() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
        }
        if (mAlbumCursor != null) {
            mAlbumCursor.close();
            mAlbumCursor = null;
        }
    }

    /**
     * Called to open a new file as the current track and prepare the next for
     * playback
     */
    private void openCurrentAndNext() {
        openCurrentAndMaybeNext(true);
    }

    /**
     * Called to open a new file as the current track and prepare the next for
     * playback
     *
     * @param openNext True to prepare the next track for playback, false
     *                 otherwise.
     */
    private void openCurrentAndMaybeNext(final boolean openNext) {
        synchronized (this) {
            closeCursor();

            if (mPlaylist.size() == 0) {
                return;
            }
            stop(false);

            boolean shutdown = false;

            updateCursor(mPlaylist.get(mPlayPos).mId);
            while (true) {
                if (mCursor != null
                        && openFile(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                        + "/" + mCursor.getLong(IDCOLIDX))) {
                    break;
                }

                // if we get here then opening the file failed. We can close the
                // cursor now, because
                // we're either going to create a new one next, or stop trying
                closeCursor();
                if (mOpenFailedCounter++ < 10 && mPlaylist.size() > 1) {
                    final int pos = getNextPosition(false);
                    if (pos < 0) {
                        shutdown = true;
                        break;
                    }
                    mPlayPos = pos;
                    stop(false);
                    mPlayPos = pos;
                    updateCursor(mPlaylist.get(mPlayPos).mId);
                } else {
                    mOpenFailedCounter = 0;
                    Log.w(TAG, "Failed to open file for playback");
                    shutdown = true;
                    break;
                }
            }

            if (shutdown) {
                scheduleDelayedShutdown();
                if (mIsSupposedToBePlaying) {
                    mIsSupposedToBePlaying = false;
                    notifyChange(PLAYSTATE_CHANGED);
                }
            } else if (openNext) {
                setNextTrack();
            }
        }
    }

    private void sendErrorMessage(final String trackName) {
        final Intent i = new Intent(TRACK_ERROR);
        i.putExtra(TrackErrorExtra.TRACK_NAME, trackName);
        sendBroadcast(i);
    }

    /**
     * @param force True to force the player onto the track next, false
     *              otherwise.
     * @return The next position to play.
     */
    private int getNextPosition(final boolean force) {
        // as a base case, if the playlist is empty just return -1
        if (mPlaylist == null || mPlaylist.isEmpty()) {
            return -1;
        }
        // if we're not forced to go to the next track and we are only playing the current track
        if (!force && mRepeatMode == REPEAT_CURRENT) {
            return Math.max(mPlayPos, 0);
        } else if (mShuffleMode == SHUFFLE_NORMAL) {
            final int numTracks = mPlaylist.size();

            // count the number of times a track has been played
            final int[] trackNumPlays = new int[numTracks];
            for (int i = 0; i < numTracks; i++) {
                // set it all to 0
                trackNumPlays[i] = 0;
            }

            // walk through the history and add up the number of times the track
            // has been played
            final int numHistory = mHistory.size();
            for (int i = 0; i < numHistory; i++) {
                final int idx = mHistory.get(i);
                if (idx >= 0 && idx < numTracks) {
                    trackNumPlays[idx]++;
                }
            }

            // also add the currently playing track to the count
            if (mPlayPos >= 0 && mPlayPos < numTracks) {
                trackNumPlays[mPlayPos]++;
            }

            // figure out the least # of times a track has a played as well as
            // how many tracks share that count
            int minNumPlays = Integer.MAX_VALUE;
            int numTracksWithMinNumPlays = 0;
            for (final int trackNumPlay : trackNumPlays) {
                // if we found a new track that has less number of plays, reset the counters
                if (trackNumPlay < minNumPlays) {
                    minNumPlays = trackNumPlay;
                    numTracksWithMinNumPlays = 1;
                } else if (trackNumPlay == minNumPlays) {
                    // increment this track shares the # of tracks
                    numTracksWithMinNumPlays++;
                }
            }

            // if we've played each track at least once and all tracks have been played an equal
            // # of times and we aren't repeating all and we're not forcing a track, then
            // return no more tracks
            if (minNumPlays > 0 && numTracksWithMinNumPlays == numTracks
                    && mRepeatMode != REPEAT_ALL && !force) {
                return -1;
            }

            // else pick a track from the least number of played tracks
            int skip = mShuffler.nextInt(numTracksWithMinNumPlays);
            for (int i = 0; i < trackNumPlays.length; i++) {
                if (trackNumPlays[i] == minNumPlays) {
                    if (skip == 0) {
                        return i;
                    } else {
                        skip--;
                    }
                }
            }

            // Unexpected to land here
            if (D) {
                Log.e(TAG, "Getting the next position resulted did not get a result " +
                        "when it should have");
            }
            return -1;
        } else if (mShuffleMode == SHUFFLE_AUTO) {
            doAutoShuffleUpdate();
            return mPlayPos + 1;
        } else {
            if (mPlayPos >= mPlaylist.size() - 1) {
                if (mRepeatMode == REPEAT_NONE && !force) {
                    return -1;
                } else if (mRepeatMode == REPEAT_ALL || force) {
                    return 0;
                }
                return -1;
            } else {
                return mPlayPos + 1;
            }
        }
    }

    /**
     * Sets the track to be played
     */
    private void setNextTrack() {
        setNextTrack(getNextPosition(false));
    }

    /**
     * Sets the next track to be played
     *
     * @param position the target position we want
     */
    private void setNextTrack(int position) {
        mNextPlayPos = position;
        if (D) Log.d(TAG, "setNextTrack: next play position = " + mNextPlayPos);
        if (mNextPlayPos >= 0 && mPlaylist != null && mNextPlayPos < mPlaylist.size()) {
            final long id = mPlaylist.get(mNextPlayPos).mId;
            mPlayer.setNextDataSource(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) + "/" + id);
        } else {
            mPlayer.setNextDataSource(null);
        }
    }

    /**
     * Creates a shuffled playlist used for party mode
     */
    private boolean makeAutoShuffleList() {
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.IS_MUSIC + "= 1",
                null,
                null
        )) {
            if (cursor == null || cursor.getCount() == 0) {
                return false;
            }
            final int len = cursor.getCount();
            final long[] list = new long[len];
            for (int i = 0; i < len; i++) {
                cursor.moveToNext();
                list[i] = cursor.getLong(0);
            }
            mAutoShuffleList = list;
            return true;
        } catch (final RuntimeException ignored) {
        }
        return false;
    }

    /**
     * Creates the party shuffle playlist
     */
    private void doAutoShuffleUpdate() {
        boolean notify = false;
        if (mPlayPos > 10) {
            removeTracks(0, mPlayPos - 9);
            notify = true;
        }
        final int toAdd = 7 - (mPlaylist.size() - (mPlayPos < 0 ? -1 : mPlayPos));
        for (int i = 0; i < toAdd; i++) {
            int idx, lookback = mHistory.size();
            while (true) {
                idx = mShuffler.nextInt(mAutoShuffleList.length);
                if (!wasRecentlyUsed(idx, lookback)) {
                    break;
                }
                lookback /= 2;
            }
            mHistory.add(idx);
            if (mHistory.size() > MAX_HISTORY_SIZE) {
                mHistory.remove(0);
            }
            mPlaylist.add(new MusicPlaybackTrack(mAutoShuffleList[idx], -1, IdType.NA, -1));
            notify = true;
        }
        if (notify) {
            notifyChange(QUEUE_CHANGED);
        }
    }

    private boolean wasRecentlyUsed(final int idx, int lookbacksize) {
        if (lookbacksize == 0) {
            return false;
        }
        final int histsize = mHistory.size();
        if (histsize < lookbacksize) {
            lookbacksize = histsize;
        }
        final int maxidx = histsize - 1;
        for (int i = 0; i < lookbacksize; i++) {
            final long entry = mHistory.get(maxidx - i);
            if (entry == idx) {
                return true;
            }
        }
        return false;
    }

    /**
     * Notify the change-receivers that something has changed.
     */
    private void notifyChange(final String what) {
        if (D) Log.d(TAG, "notifyChange: what = " + what);

        // Update the lockscreen controls
        updateMediaSession(what);

        if (what.equals(POSITION_CHANGED)) {
            return;
        }

        final Intent intent = new Intent(what);
        intent.putExtra("id", getAudioId());
        intent.putExtra("artist", getArtistName());
        intent.putExtra("album", getAlbumName());
        intent.putExtra("track", getTrackName());
        intent.putExtra("playing", isPlaying());

        if (NEW_LYRICS.equals(what)) {
            intent.putExtra("lyrics", mLyrics);
        }

        sendStickyBroadcast(intent);

        final Intent musicIntent = new Intent(intent);
        musicIntent.setAction(what.replace(ELEVEN_PACKAGE_NAME, MUSIC_PACKAGE_NAME));
        sendStickyBroadcast(musicIntent);

        if (META_CHANGED.equals(what)) {
            // Add the track to the recently played list.
            mRecentsCache.addSongId(getAudioId());

            mSongPlayCountCache.bumpSongCount(getAudioId());
        } else if (QUEUE_CHANGED.equals(what) || QUEUE_MOVED.equals(what)) {
            saveQueue(true);
            if (isPlaying()) {
                // if we are in shuffle mode and our next track is still valid,
                // try to re-use the track
                // We need to reimplement the queue to prevent hacky solutions like this
                if (mNextPlayPos >= 0 && mNextPlayPos < mPlaylist.size()
                        && getShuffleMode() != SHUFFLE_NONE) {
                    setNextTrack(mNextPlayPos);
                } else {
                    setNextTrack();
                }
            }
        } else {
            saveQueue(false);
        }

        if (what.equals(PLAYSTATE_CHANGED) || what.equals(META_CHANGED)) {
            updateNotification();
        }

        // Update the app-widgets
        mAppWidgetSmall.notifyChange(this, what);
        mAppWidgetLarge.notifyChange(this, what);
        mAppWidgetLargeAlternate.notifyChange(this, what);
    }

    private void updateMediaSession(final String what) {
        int playState = mIsSupposedToBePlaying
                ? PlaybackState.STATE_PLAYING
                : PlaybackState.STATE_PAUSED;

        long playBackStateActions = PlaybackState.ACTION_PLAY |
                PlaybackState.ACTION_PLAY_PAUSE |
                PlaybackState.ACTION_PLAY_FROM_MEDIA_ID |
                PlaybackState.ACTION_PAUSE |
                PlaybackState.ACTION_SKIP_TO_NEXT |
                PlaybackState.ACTION_SEEK_TO |
                PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                PlaybackState.ACTION_STOP;

        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                .setActions(playBackStateActions)
                .setActiveQueueItemId(getAudioId())
                .setState(playState, position(), 1.0f);

        // create custom action
        stateBuilder.addCustomAction(new PlaybackState.CustomAction.Builder(
                SHUFFLE_ACTION,
                getString(R.string.menu_shuffle_item),
                R.drawable.btn_playback_shuffle_all).build());
        stateBuilder.addCustomAction(new PlaybackState.CustomAction.Builder(
                REPEAT_ACTION,
                getString(R.string.accessibility_repeat),
                R.drawable.btn_playback_repeat_all).build());

        if (what.equals(PLAYSTATE_CHANGED) || what.equals(POSITION_CHANGED)) {
            mSession.setPlaybackState(stateBuilder.build());
        } else if (what.equals(META_CHANGED) || what.equals(QUEUE_CHANGED)
                || QUEUE_MOVED.equals(what)) {
            Bitmap albumArt = getAlbumArt(false).getBitmap();
            if (albumArt != null) {
                // RemoteControlClient wants to recycle the bitmaps thrown at it, so we need
                // to make sure not to hand out our cache copy
                Bitmap.Config config = albumArt.getConfig();
                if (config == null) {
                    config = Bitmap.Config.ARGB_8888;
                }
                albumArt = albumArt.copy(config, false);
            }

            mSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, getArtistName())
                    .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, getAlbumArtistName())
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, getAlbumName())
                    .putString(MediaMetadata.METADATA_KEY_TITLE, getTrackName())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
                    .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, getQueuePosition() + 1)
                    .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, getQueue().length)
                    .putString(MediaMetadata.METADATA_KEY_GENRE, getGenreName())
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, albumArt)
                    .build());

            if (what.equals(QUEUE_CHANGED) || what.equals(QUEUE_MOVED)) {
                updateMediaSessionQueue();
            }

            mSession.setPlaybackState(stateBuilder.build());
        }
    }

    private synchronized void updateMediaSessionQueue() {
        if (mQueueUpdateTask != null) {
            mQueueUpdateTask.cancel();
        }
        mQueueUpdateTask = new QueueUpdateTask(getQueue());
        mQueueUpdateTask.execute();
    }

    private Notification buildNotification() {
        final String albumName = getAlbumName();
        final String artistName = getArtistName();
        final boolean isPlaying = isPlaying();
        String text = TextUtils.isEmpty(albumName)
                ? artistName : artistName + " - " + albumName;

        int playButtonResId = isPlaying
                ? R.drawable.btn_playback_pause : R.drawable.btn_playback_play;
        int playButtonTitleResId = isPlaying
                ? R.string.accessibility_pause : R.string.accessibility_play;

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setMediaSession(mSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2);

        Intent nowPlayingIntent = new Intent(ACTION_AUDIO_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent clickIntent = PendingIntent.getActivity(this, 0, nowPlayingIntent,
                PendingIntent.FLAG_IMMUTABLE);
        BitmapWithColors artwork = getAlbumArt(false);

        if (mNotificationPostTime == 0) {
            mNotificationPostTime = SystemClock.elapsedRealtime();
        }

        NotificationChannel channel = mNotificationManager.getNotificationChannel(CHANNEL_NAME);
        if (channel == null) {
            String name = getString(R.string.channel_music);

            channel = new NotificationChannel(CHANNEL_NAME, name,
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            mNotificationManager.createNotificationChannel(channel);
        }

        final Notification.Action prevAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.btn_playback_previous),
                getString(R.string.accessibility_prev),
                retrievePlaybackAction(PREVIOUS_ACTION))
                .build();
        final Notification.Action togglePauseAction = new Notification.Action.Builder(
                Icon.createWithResource(this, playButtonResId),
                getString(playButtonTitleResId),
                retrievePlaybackAction(TOGGLEPAUSE_ACTION))
                .build();
        final Notification.Action nextAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.btn_playback_next),
                getString(R.string.accessibility_next),
                retrievePlaybackAction(NEXT_ACTION))
                .build();

        return new Notification.Builder(this, CHANNEL_NAME)
                .setChannelId(channel.getId())
                .setSmallIcon(R.drawable.ic_song)
                .setLargeIcon(artwork.getBitmap())
                .setContentIntent(clickIntent)
                .setContentTitle(getTrackName())
                .setContentText(text)
                .setColor(artwork.getVibrantColor())
                .setWhen(mNotificationPostTime)
                .setStyle(style)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(prevAction)
                .addAction(togglePauseAction)
                .addAction(nextAction)
                .build();
    }

    private PendingIntent retrievePlaybackAction(final String action) {
        final ComponentName serviceName = new ComponentName(this, MusicPlaybackService.class);
        Intent intent = new Intent(action);
        intent.setComponent(serviceName);

        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Saves the queue
     *
     * @param full True if the queue is full
     */
    private void saveQueue(final boolean full) {
        if (!mQueueIsSaveable || mPreferences == null) {
            return;
        }

        final SharedPreferences.Editor editor = mPreferences.edit();
        if (full) {
            mPlaybackStateStore.saveState(mPlaylist,
                    mShuffleMode != SHUFFLE_NONE ? mHistory : null);
            editor.putInt("cardid", mCardId);
        }
        editor.putInt("curpos", mPlayPos);
        if (mPlayer.isInitialized()) {
            editor.putLong("seekpos", mPlayer.position());
        }
        editor.putInt("repeatmode", mRepeatMode);
        editor.putInt("shufflemode", mShuffleMode);
        editor.apply();
    }

    /**
     * Reloads the queue as the user left it the last time they stopped using
     * Eleven
     */
    private void reloadQueue() {
        int id = mCardId;
        if (mPreferences.contains("cardid")) {
            id = mPreferences.getInt("cardid", ~mCardId);
        }
        if (id == mCardId) {
            mPlaylist = mPlaybackStateStore.getQueue();
        }
        if (mPlaylist.size() > 0) {
            final int pos = mPreferences.getInt("curpos", 0);
            if (pos < 0 || pos >= mPlaylist.size()) {
                mPlaylist.clear();
                return;
            }
            mPlayPos = pos;
            updateCursor(mPlaylist.get(mPlayPos).mId);
            if (mCursor == null) {
                SystemClock.sleep(3000);
                updateCursor(mPlaylist.get(mPlayPos).mId);
            }
            synchronized (this) {
                closeCursor();
                mOpenFailedCounter = 20;
                openCurrentAndNext();
            }
            if (!mPlayer.isInitialized()) {
                mPlaylist.clear();
                return;
            }

            final long seekpos = mPreferences.getLong("seekpos", 0);
            seek(seekpos >= 0 && seekpos < duration() ? seekpos : 0);

            if (D) {
                Log.d(TAG, "restored queue, currently at position "
                        + position() + "/" + duration()
                        + " (requested " + seekpos + ")");
            }

            int repmode = mPreferences.getInt("repeatmode", REPEAT_NONE);
            if (repmode != REPEAT_ALL && repmode != REPEAT_CURRENT) {
                repmode = REPEAT_NONE;
            }
            mRepeatMode = repmode;

            int shufmode = mPreferences.getInt("shufflemode", SHUFFLE_NONE);
            if (shufmode != SHUFFLE_AUTO && shufmode != SHUFFLE_NORMAL) {
                shufmode = SHUFFLE_NONE;
            }
            if (shufmode != SHUFFLE_NONE) {
                mHistory = mPlaybackStateStore.getHistory(mPlaylist.size());
            }
            if (shufmode == SHUFFLE_AUTO) {
                if (!makeAutoShuffleList()) {
                    shufmode = SHUFFLE_NONE;
                }
            }
            mShuffleMode = shufmode;
        }
    }

    /**
     * Opens a file and prepares it for playback
     *
     * @param path The path of the file to open
     */
    public boolean openFile(final String path) {
        if (D) Log.d(TAG, "openFile: path = " + path);
        synchronized (this) {
            if (path == null) {
                return false;
            }

            // If mCursor is null, try to associate path with a database cursor
            if (mCursor == null) {
                Uri uri = Uri.parse(path);
                boolean shouldAddToPlaylist = true;     // should try adding audio info to playlist
                long id = -1;
                try {
                    id = Long.parseLong(uri.getLastPathSegment());
                } catch (NumberFormatException ex) {
                    // Ignore
                }

                if (id != -1 && path.startsWith(
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL).toString()
                )) {
                    updateCursor(uri);

                } else if (id != -1 && path.startsWith(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL).toString())) {
                    updateCursor(id);

                    // handle downloaded media files
                } else if (path.startsWith("content://downloads/")) {

                    // extract MediaProvider(MP) uri , if available
                    // Downloads.Impl.COLUMN_MEDIAPROVIDER_URI
                    String mpUri = getValueForDownloadedFile(this, uri, "mediaprovider_uri");
                    if (D) Log.i(TAG, "Downloaded file's MP uri : " + mpUri);
                    if (!TextUtils.isEmpty(mpUri)) {
                        // if mpUri is valid, play that URI instead
                        if (openFile(mpUri)) {
                            // notify impending change in track
                            notifyChange(META_CHANGED);
                            return true;
                        } else {
                            return false;
                        }
                    } else {
                        // create phantom cursor with download info, if a MP uri wasn't found
                        updateCursorForDownloadedFile(uri);
                        shouldAddToPlaylist = false;    // song info isn't available in MediaStore
                    }

                } else {
                    // assuming a "file://" uri by this point ...
                    String where = MediaStore.Audio.Media.DATA + "=?";
                    String[] selectionArgs = new String[]{path};
                    updateCursor(where, selectionArgs);
                }
                try {
                    if (mCursor != null && shouldAddToPlaylist) {
                        mPlaylist.clear();
                        mPlaylist.add(new MusicPlaybackTrack(
                                mCursor.getLong(IDCOLIDX), -1, IdType.NA, -1));
                        // propagate the change in playlist state
                        notifyChange(QUEUE_CHANGED);
                        mPlayPos = 0;
                        mHistory.clear();
                    }
                } catch (final UnsupportedOperationException ex) {
                    // Ignore
                }
            }

            mFileToPlay = path;
            mPlayer.setDataSource(mFileToPlay);
            if (mPlayer.isInitialized()) {
                mOpenFailedCounter = 0;
                return true;
            }

            String trackName = getTrackName();
            if (TextUtils.isEmpty(trackName)) {
                trackName = path;
            }
            sendErrorMessage(trackName);

            stop(true);
            return false;
        }
    }

    /*
        Columns for a pseudo cursor we are creating for downloaded songs
        Modeled after mCursor to be able to respond to respond to the same queries as it
     */
    private static final String[] PROJECTION_MATRIX = new String[]{
            "_id", MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID
    };

    /**
     * Creates a pseudo cursor for downloaded audio files with minimal info
     *
     * @param uri the uri of the downloaded file
     */
    private void updateCursorForDownloadedFile(Uri uri) {
        synchronized (this) {
            closeCursor();  // clear mCursor
            MatrixCursor cursor = new MatrixCursor(PROJECTION_MATRIX);
            // get title of the downloaded file ; Downloads.Impl.COLUMN_TITLE
            String title = getValueForDownloadedFile(this, uri, "title");
            // populating the cursor with bare minimum info
            cursor.addRow(new Object[]{
                    null,
                    null,
                    null,
                    title,
                    null,
                    null,
                    null,
                    null
            });
            mCursor = cursor;
            mCursor.moveToFirst();
        }
    }

    /**
     * Query the DownloadProvider to get the value in the specified column
     *
     * @param uri the uri of the downloaded file
     */
    private String getValueForDownloadedFile(Context context, Uri uri, String column) {

        final String[] projection = {
                column
        };
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        }
        return null;
    }

    /**
     * Returns the audio session ID
     *
     * @return The current media player audio session ID
     */
    public int getAudioSessionId() {
        synchronized (this) {
            return mPlayer.getAudioSessionId();
        }
    }

    /**
     * Indicates if the media storeage device has been mounted or not
     *
     * @return 1 if Intent.ACTION_MEDIA_MOUNTED is called, 0 otherwise
     */
    public int getMediaMountedCount() {
        return mMediaMountedCount;
    }

    /**
     * Returns the shuffle mode
     *
     * @return The current shuffle mode (all, party, none)
     */
    public int getShuffleMode() {
        return mShuffleMode;
    }

    /**
     * Returns the repeat mode
     *
     * @return The current repeat mode (all, one, none)
     */
    public int getRepeatMode() {
        return mRepeatMode;
    }

    /**
     * Removes all instances of the track with the given ID from the playlist.
     *
     * @param id The id to be removed
     * @return how many instances of the track were removed
     */
    public int removeTrack(final long id) {
        int numremoved = 0;
        synchronized (this) {
            for (int i = 0; i < mPlaylist.size(); i++) {
                if (mPlaylist.get(i).mId == id) {
                    numremoved += removeTracksInternal(i, i);
                    i--;
                }
            }
        }
        if (numremoved > 0) {
            notifyChange(QUEUE_CHANGED);
        }
        return numremoved;
    }

    /**
     * Removes a song from the playlist at the specified position.
     *
     * @param id       The song id to be removed
     * @param position The position of the song in the playlist
     * @return true if successful
     */
    public boolean removeTrackAtPosition(final long id, final int position) {
        synchronized (this) {
            if (position >= 0 &&
                    position < mPlaylist.size() &&
                    mPlaylist.get(position).mId == id) {

                return removeTracks(position, position) > 0;
            }
        }
        return false;
    }

    /**
     * Removes the range of tracks specified from the play list. If a file
     * within the range is the file currently being played, playback will move
     * to the next file after the range.
     *
     * @param first The first file to be removed
     * @param last  The last file to be removed
     * @return the number of tracks deleted
     */
    public int removeTracks(final int first, final int last) {
        final int numremoved = removeTracksInternal(first, last);
        if (numremoved > 0) {
            notifyChange(QUEUE_CHANGED);
        }
        return numremoved;
    }

    /**
     * Returns the position in the queue
     *
     * @return the current position in the queue
     */
    public int getQueuePosition() {
        synchronized (this) {
            return mPlayPos;
        }
    }

    /**
     * @return the size of the queue history cache
     */
    public int getQueueHistorySize() {
        synchronized (this) {
            return mHistory.size();
        }
    }

    /**
     * @return the position in the history
     */
    public int getQueueHistoryPosition(int position) {
        synchronized (this) {
            if (position >= 0 && position < mHistory.size()) {
                return mHistory.get(position);
            }
        }

        return -1;
    }

    /**
     * @return the queue of history positions
     */
    public int[] getQueueHistoryList() {
        synchronized (this) {
            int[] history = new int[mHistory.size()];
            for (int i = 0; i < mHistory.size(); i++) {
                history[i] = mHistory.get(i);
            }

            return history;
        }
    }

    /**
     * Returns the path to current song
     *
     * @return The path to the current song
     */
    public String getPath() {
        synchronized (this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(AudioColumns.DATA));
        }
    }

    /**
     * Returns the album name
     *
     * @return The current song album Name
     */
    public String getAlbumName() {
        synchronized (this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(AudioColumns.ALBUM));
        }
    }

    /**
     * Returns the song name
     *
     * @return The current song name
     */
    public String getTrackName() {
        synchronized (this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(AudioColumns.TITLE));
        }
    }

    /**
     * Returns the genre name of song
     *
     * @return The current song genre name
     */
    public String getGenreName() {
        synchronized (this) {
            if (mCursor == null || mPlayPos < 0 || mPlayPos >= mPlaylist.size()) {
                return null;
            }
            String[] genreProjection = {MediaStore.Audio.Genres.NAME};
            Uri genreUri = MediaStore.Audio.Genres.getContentUriForAudioId("external",
                    (int) mPlaylist.get(mPlayPos).mId);
            Cursor genreCursor = getContentResolver().query(genreUri, genreProjection,
                    null, null, null);
            if (genreCursor != null) {
                try {
                    if (genreCursor.moveToFirst()) {
                        return genreCursor.getString(
                                genreCursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME));
                    }
                } finally {
                    genreCursor.close();
                }
            }
            return null;
        }
    }

    /**
     * Returns the artist name
     *
     * @return The current song artist name
     */
    public String getArtistName() {
        synchronized (this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(AudioColumns.ARTIST));
        }
    }

    /**
     * Returns the artist name
     *
     * @return The current song artist name
     */
    public String getAlbumArtistName() {
        synchronized (this) {
            if (mAlbumCursor == null) {
                return null;
            }
            return mAlbumCursor.getString(mAlbumCursor.getColumnIndexOrThrow(AlbumColumns.ARTIST));
        }
    }

    /**
     * Returns the album ID
     *
     * @return The current song album ID
     */
    public long getAlbumId() {
        synchronized (this) {
            if (mCursor == null) {
                return -1;
            }
            return mCursor.getLong(mCursor.getColumnIndexOrThrow(AudioColumns.ALBUM_ID));
        }
    }

    /**
     * Returns the artist ID
     *
     * @return The current song artist ID
     */
    public long getArtistId() {
        synchronized (this) {
            if (mCursor == null) {
                return -1;
            }
            return mCursor.getLong(mCursor.getColumnIndexOrThrow(AudioColumns.ARTIST_ID));
        }
    }

    /**
     * @return The audio id of the track
     */
    public long getAudioId() {
        MusicPlaybackTrack track = getCurrentTrack();
        if (track != null) {
            return track.mId;
        }

        return -1;
    }

    /**
     * Gets the currently playing music track
     */
    public MusicPlaybackTrack getCurrentTrack() {
        return getTrack(mPlayPos);
    }

    /**
     * Gets the music track from the queue at the specified index
     *
     * @param index position
     * @return music track or null
     */
    public synchronized MusicPlaybackTrack getTrack(int index) {
        if (index >= 0 && index < mPlaylist.size() && mPlayer.isInitialized()) {
            return mPlaylist.get(index);
        }

        return null;
    }

    /**
     * Returns the next audio ID
     *
     * @return The next track ID
     */
    public long getNextAudioId() {
        synchronized (this) {
            if (mNextPlayPos >= 0 && mNextPlayPos < mPlaylist.size() && mPlayer.isInitialized()) {
                return mPlaylist.get(mNextPlayPos).mId;
            }
        }
        return -1;
    }

    /**
     * Returns the previous audio ID
     *
     * @return The previous track ID
     */
    public long getPreviousAudioId() {
        synchronized (this) {
            if (mPlayer.isInitialized()) {
                int pos = getPreviousPlayPosition(false);
                if (pos >= 0 && pos < mPlaylist.size()) {
                    return mPlaylist.get(pos).mId;
                }
            }
        }
        return -1;
    }

    /**
     * Seeks the current track to a specific time
     *
     * @param position The time to seek to
     * @return The time to play the track at
     */
    public long seek(long position) {
        if (mPlayer.isInitialized()) {
            if (position < 0) {
                position = 0;
            } else if (position > mPlayer.duration()) {
                position = mPlayer.duration();
            }
            long result = mPlayer.seek(position);
            notifyChange(POSITION_CHANGED);
            return result;
        }
        return -1;
    }

    /**
     * Seeks the current track to a position relative to its current position
     * If the relative position is after or before the track, it will also automatically
     * jump to the previous or next track respectively
     *
     * @param deltaInMs The delta time to seek to in milliseconds
     */
    public void seekRelative(long deltaInMs) {
        synchronized (this) {
            if (mPlayer.isInitialized()) {
                final long newPos = position() + deltaInMs;
                final long duration = duration();
                if (newPos < 0) {
                    prev(true);
                    // seek to the new duration + the leftover position
                    seek(duration() + newPos);
                } else if (newPos >= duration) {
                    gotoNext(true);
                    // seek to the leftover duration
                    seek(newPos - duration);
                } else {
                    seek(newPos);
                }
            }
        }
    }

    /**
     * Returns the current position in time of the currenttrack
     *
     * @return The current playback position in miliseconds
     */
    public long position() {
        if (mPlayer.isInitialized()) {
            return mPlayer.position();
        }
        return -1;
    }

    /**
     * Returns the full duration of the current track
     *
     * @return The duration of the current track in miliseconds
     */
    public long duration() {
        if (mPlayer.isInitialized()) {
            return mPlayer.duration();
        }
        return -1;
    }

    /**
     * Returns the queue
     *
     * @return The queue as a long[]
     */
    public long[] getQueue() {
        synchronized (this) {
            final int len = mPlaylist.size();
            final long[] list = new long[len];
            for (int i = 0; i < len; i++) {
                list[i] = mPlaylist.get(i).mId;
            }
            return list;
        }
    }

    /**
     * Gets the track id at a given position in the queue
     *
     * @return track id in the queue position
     */
    public long getQueueItemAtPosition(int position) {
        synchronized (this) {
            if (position >= 0 && position < mPlaylist.size()) {
                return mPlaylist.get(position).mId;
            }
        }

        return -1;
    }

    /**
     * @return the size of the queue
     */
    public int getQueueSize() {
        synchronized (this) {
            return mPlaylist.size();
        }
    }

    /**
     * @return True if music is playing, false otherwise
     */
    public boolean isPlaying() {
        return mIsSupposedToBePlaying;
    }

    /**
     * Helper function to wrap the logic around mIsSupposedToBePlaying for consistentcy
     *
     * @param value  to set mIsSupposedToBePlaying to
     * @param notify whether we want to fire PLAYSTATE_CHANGED event
     */
    private void setIsSupposedToBePlaying(boolean value, boolean notify) {
        if (mIsSupposedToBePlaying != value) {
            mIsSupposedToBePlaying = value;

            // Update mLastPlayed time first and notify afterwards, as
            // the notification listener method needs the up-to-date value
            // for the recentlyPlayed() method to work
            if (mIsSupposedToBePlaying) {
                cancelShutdown();
                // Make sure we're started explicitly, so that we aren't killed when
                // the player activity unbinds
                startForegroundService(new Intent(this, MusicPlaybackService.class));
            } else {
                scheduleDelayedShutdown();
                mLastPlayedTime = SystemClock.elapsedRealtime();
            }

            if (notify) {
                notifyChange(PLAYSTATE_CHANGED);
            }
        }
    }

    /**
     * @return true if is playing or has played within the last IDLE_DELAY time
     */
    private boolean recentlyPlayed() {
        return isPlaying() || SystemClock.elapsedRealtime() - mLastPlayedTime < IDLE_DELAY;
    }

    /**
     * Opens a list for playback
     *
     * @param list     The list of tracks to open
     * @param position The position to start playback at
     */
    public void open(final long[] list, final int position, long sourceId, IdType sourceType) {
        synchronized (this) {
            if (mShuffleMode == SHUFFLE_AUTO) {
                mShuffleMode = SHUFFLE_NORMAL;
            }
            final long oldId = getAudioId();
            final int listlength = list.length;
            boolean newlist = true;
            if (mPlaylist.size() == listlength) {
                newlist = false;
                for (int i = 0; i < listlength; i++) {
                    if (list[i] != mPlaylist.get(i).mId) {
                        newlist = true;
                        break;
                    }
                }
            }
            if (newlist) {
                addToPlayList(list, -1, sourceId, sourceType);
            }
            if (position >= 0) {
                mPlayPos = position;
            } else {
                mPlayPos = mShuffler.nextInt(mPlaylist.size());
            }
            mHistory.clear();
            openCurrentAndNext();
            if (oldId != getAudioId()) {
                notifyChange(META_CHANGED);
            }
        }
    }

    /**
     * Stops playback.
     */
    public void stop() {
        stopShakeDetector(false);
        stop(true);
    }

    /**
     * Resumes or starts playback.
     */
    public void play() {
        startShakeDetector();
        play(true);
    }

    /**
     * Resumes or starts playback.
     *
     * @param createNewNextTrack True if you want to figure out the next track, false
     *                           if you want to re-use the existing next track (used for going back)
     */
    public void play(boolean createNewNextTrack) {
        int status = mAudioManager.requestAudioFocus(mAudioFocusRequest);

        if (D) Log.d(TAG, "Starting playback: audio focus request status = " + status);

        if (status != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return;
        }

        final Intent intent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        sendBroadcast(intent);

        mSession.setActive(true);

        if (createNewNextTrack) {
            setNextTrack();
        } else {
            setNextTrack(mNextPlayPos);
        }

        if (mPlayer.isInitialized()) {
            final long duration = mPlayer.duration();
            if (mRepeatMode != REPEAT_CURRENT && duration > 2000
                    && mPlayer.position() >= duration - 2000) {
                gotoNext(true);
            }

            mPlayer.start();
            mPlayerHandler.removeMessages(FADEDOWN);
            mPlayerHandler.sendEmptyMessage(FADEUP);

            setIsSupposedToBePlaying(true, true);
        } else if (mPlaylist.size() <= 0) {
            setShuffleMode(SHUFFLE_AUTO);
        }
    }

    private void togglePlayPause() {
        if (isPlaying()) {
            pause(false);
        } else {
            play();
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (D) Log.d(TAG, "Received audio focus change event " + focusChange);
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                pause(focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                mPlayerHandler.removeMessages(FADEUP);
                mPlayerHandler.sendEmptyMessage(FADEDOWN);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (!isPlaying() && mPausedByTransientLossOfFocus) {
                    mPausedByTransientLossOfFocus = false;
                    mPlayerHandler.mCurrentVolume = 0f;
                    mPlayer.setVolume(0f);
                    play();
                } else {
                    mPlayerHandler.removeMessages(FADEDOWN);
                    mPlayerHandler.sendEmptyMessage(FADEUP);
                }
                break;
            default:
                break;
        }
    }

    /**
     * Temporarily pauses playback.
     */
    public void pause(boolean dueToFocusLoss) {
        if (mPlayerHandler == null) return;
        if (D) Log.d(TAG, "Pausing playback");
        synchronized (this) {
            if (mPlayerHandler != null) {
                mPlayerHandler.removeMessages(FADEUP);
            }
            mPausedByTransientLossOfFocus = mIsSupposedToBePlaying && dueToFocusLoss;
            if (mIsSupposedToBePlaying) {
                final Intent intent = new Intent(
                        AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
                intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
                intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
                sendBroadcast(intent);

                if (mPlayer != null) {
                    mPlayer.pause();
                }
                setIsSupposedToBePlaying(false, true);
                stopShakeDetector(false);
            }
        }
    }

    /**
     * Changes from the current track to the next track
     */
    public void gotoNext(final boolean force) {
        if (D) Log.d(TAG, "Going to next track");
        synchronized (this) {
            if (mPlaylist.size() <= 0) {
                if (D) Log.d(TAG, "No play queue");
                scheduleDelayedShutdown();
                return;
            }
            int pos = mNextPlayPos;
            if (pos < 0) {
                pos = getNextPosition(force);
            }

            if (pos < 0) {
                setIsSupposedToBePlaying(false, true);
                return;
            }

            stop(false);
            setAndRecordPlayPos(pos);
            openCurrentAndNext();
            play();
            notifyChange(META_CHANGED);
        }
    }

    public void setAndRecordPlayPos(int nextPos) {
        synchronized (this) {
            // save to the history
            if (mShuffleMode != SHUFFLE_NONE) {
                mHistory.add(mPlayPos);
                if (mHistory.size() > MAX_HISTORY_SIZE) {
                    mHistory.remove(0);
                }
            }

            mPlayPos = nextPos;
        }
    }

    /**
     * Changes from the current track to the previous played track
     */
    public void prev(boolean forcePrevious) {
        synchronized (this) {
            // if we aren't repeating 1, and we are either early in the song
            // or we want to force go back, then go to the prevous track
            boolean goPrevious = getRepeatMode() != REPEAT_CURRENT &&
                    (position() < REWIND_INSTEAD_PREVIOUS_THRESHOLD || forcePrevious);

            if (goPrevious) {
                if (D) Log.d(TAG, "Going to previous track");
                int pos = getPreviousPlayPosition(true);
                // if we have no more previous tracks, quit
                if (pos < 0) {
                    return;
                }
                mNextPlayPos = mPlayPos;
                mPlayPos = pos;
                stop(false);
                openCurrent();
                play(false);
                notifyChange(META_CHANGED);
            } else {
                if (D) Log.d(TAG, "Going to beginning of track");
                seek(0);
                play(false);
            }
        }
    }

    public int getPreviousPlayPosition(boolean removeFromHistory) {
        synchronized (this) {
            if (mShuffleMode == SHUFFLE_NORMAL) {
                // Go to previously-played track and remove it from the history
                final int histsize = mHistory.size();
                if (histsize == 0) {
                    return -1;
                }
                final Integer pos = mHistory.get(histsize - 1);
                if (removeFromHistory) {
                    mHistory.remove(histsize - 1);
                }
                return pos;
            } else {
                if (mPlayPos > 0) {
                    return mPlayPos - 1;
                } else {
                    return mPlaylist.size() - 1;
                }
            }
        }
    }

    /**
     * We don't want to open the current and next track when the user is using
     * the {@code #prev()} method because they won't be able to travel back to
     * the previously listened track if they're shuffling.
     */
    private void openCurrent() {
        openCurrentAndMaybeNext(false);
    }

    /**
     * Moves an item in the queue from one position to another
     *
     * @param index1 The position the item is currently at
     * @param index2 The position the item is being moved to
     */
    public void moveQueueItem(int index1, int index2) {
        synchronized (this) {
            if (index1 >= mPlaylist.size()) {
                index1 = mPlaylist.size() - 1;
            }
            if (index2 >= mPlaylist.size()) {
                index2 = mPlaylist.size() - 1;
            }

            if (index1 == index2) {
                return;
            }

            final MusicPlaybackTrack track = mPlaylist.remove(index1);
            mPlaylist.add(index2, track);
            if (index1 < index2) {
                if (mPlayPos == index1) {
                    mPlayPos = index2;
                } else if (mPlayPos >= index1 && mPlayPos <= index2) {
                    mPlayPos--;
                }
            } else {
                if (mPlayPos == index1) {
                    mPlayPos = index2;
                } else if (mPlayPos >= index2 && mPlayPos <= index1) {
                    mPlayPos++;
                }
            }
            notifyChange(QUEUE_MOVED);
        }
    }

    /**
     * Sets the repeat mode
     *
     * @param repeatmode The repeat mode to use
     */
    public void setRepeatMode(final int repeatmode) {
        synchronized (this) {
            mRepeatMode = repeatmode;
            setNextTrack();
            saveQueue(false);
            notifyChange(REPEATMODE_CHANGED);
        }
    }

    /**
     * Sets the shuffle mode
     *
     * @param shufflemode The shuffle mode to use
     */
    public void setShuffleMode(final int shufflemode) {
        synchronized (this) {
            if (mShuffleMode == shufflemode && mPlaylist.size() > 0) {
                return;
            }

            mShuffleMode = shufflemode;
            if (mShuffleMode == SHUFFLE_AUTO) {
                if (makeAutoShuffleList()) {
                    mPlaylist.clear();
                    doAutoShuffleUpdate();
                    mPlayPos = 0;
                    openCurrentAndNext();
                    play();
                    notifyChange(META_CHANGED);
                    return;
                } else {
                    mShuffleMode = SHUFFLE_NONE;
                }
            } else {
                setNextTrack();
            }
            saveQueue(false);
            notifyChange(SHUFFLEMODE_CHANGED);
        }
    }

    /**
     * Sets the position of a track in the queue
     *
     * @param index The position to place the track
     */
    public void setQueuePosition(final int index) {
        synchronized (this) {
            stop(false);
            mPlayPos = index;
            openCurrentAndNext();
            play();
            notifyChange(META_CHANGED);
            if (mShuffleMode == SHUFFLE_AUTO) {
                doAutoShuffleUpdate();
            }
        }
    }

    /**
     * Queues a new list for playback
     *
     * @param list   The list to queue
     * @param action The action to take
     */
    public void enqueue(final long[] list, final int action, long sourceId, IdType sourceType) {
        synchronized (this) {
            if (action == NEXT && mPlayPos + 1 < mPlaylist.size()) {
                addToPlayList(list, mPlayPos + 1, sourceId, sourceType);
                mNextPlayPos = mPlayPos + 1;
            } else {
                addToPlayList(list, Integer.MAX_VALUE, sourceId, sourceType);
            }

            if (mPlayPos < 0) {
                mPlayPos = 0;
                openCurrentAndNext();
                play();
                notifyChange(META_CHANGED);
            }
        }
    }

    /**
     * Cycles through the different repeat modes
     */
    private void cycleRepeat() {
        if (mRepeatMode == REPEAT_NONE) {
            setRepeatMode(REPEAT_ALL);
        } else if (mRepeatMode == REPEAT_ALL) {
            setRepeatMode(REPEAT_CURRENT);
            if (mShuffleMode != SHUFFLE_NONE) {
                setShuffleMode(SHUFFLE_NONE);
            }
        } else {
            setRepeatMode(REPEAT_NONE);
        }
    }

    /**
     * Cycles through the different shuffle modes
     */
    private void cycleShuffle() {
        if (mShuffleMode == SHUFFLE_NONE) {
            setShuffleMode(SHUFFLE_NORMAL);
            if (mRepeatMode == REPEAT_CURRENT) {
                setRepeatMode(REPEAT_ALL);
            }
        } else if (mShuffleMode == SHUFFLE_NORMAL || mShuffleMode == SHUFFLE_AUTO) {
            setShuffleMode(SHUFFLE_NONE);
        }
    }

    /**
     * @param smallBitmap true to return a smaller version of the default artwork image.
     *                    Currently Has no impact on the artwork size if one exists
     * @return The album art for the current album.
     */
    public BitmapWithColors getAlbumArt(boolean smallBitmap) {
        final String albumName = getAlbumName();
        final String artistName = getArtistName();
        final long albumId = getAlbumId();
        final String key = albumName + "_" + artistName + "_" + albumId;
        final int targetIndex = smallBitmap ? 0 : 1;

        // if the cached key matches and we have the bitmap, return it
        if (key.equals(mCachedKey) && mCachedBitmapWithColors[targetIndex] != null) {
            return mCachedBitmapWithColors[targetIndex];
        }

        // otherwise get the artwork (or default artwork if none found)
        final BitmapWithColors bitmap = mImageFetcher.getArtwork(albumName, albumId, smallBitmap);

        // if the key is different, clear the bitmaps first
        if (!key.equals(mCachedKey)) {
            mCachedBitmapWithColors[0] = null;
            mCachedBitmapWithColors[1] = null;
        }

        // store the new key and bitmap
        mCachedKey = key;
        mCachedBitmapWithColors[targetIndex] = bitmap;
        return bitmap;
    }

    /**
     * Called when one of the lists should refresh or requery.
     */
    public void refresh() {
        notifyChange(REFRESH);
    }

    /**
     * Called when one of the playlists have changed (renamed, added/removed tracks)
     */
    public void playlistChanged() {
        notifyChange(PLAYLIST_CHANGED);
    }

    /**
     * Called to set the status of shake to play feature
     */
    public void setShakeToPlayEnabled(boolean enabled) {
        if (D) {
            Log.d(TAG, "ShakeToPlay status: " + enabled);
        }
        if (enabled) {
            if (mShakeDetector == null) {
                mShakeDetector = new ShakeDetector(() -> {
                    if (D) Log.d(TAG, "Shake detected");
                    gotoNext(true);
                });
            }
            // if song is already playing, start listening immediately
            if (isPlaying()) {
                startShakeDetector();
            }
        } else {
            stopShakeDetector(true);
        }
    }

    /**
     * Called to start listening to shakes
     */
    private void startShakeDetector() {
        if (mShakeDetector != null) {
            mShakeDetector.start(getSystemService(SensorManager.class));
        }
    }

    /**
     * Called to stop listening to shakes
     */
    private void stopShakeDetector(final boolean destroyShakeDetector) {
        if (mShakeDetector != null) {
            mShakeDetector.stop();
        }
        if (destroyShakeDetector) {
            mShakeDetector = null;
            if (D) {
                Log.d(TAG, "ShakeToPlay destroyed!!!");
            }
        }
    }

    /**
     * Setup root menu items for Android Auto to use
     */
    private void setupRootMediaItems() {
        List<MediaBrowser.MediaItem> result = new ArrayList<>();
        MediaDescription albums = new MediaDescription.Builder()
                .setMediaId(CONTENT_BROWSER_ALBUMS)
                .setTitle("Albums")
                .setIconUri(Uri.parse(mResourceUri + getApplicationContext().getResources()
                        .getResourceEntryName(R.drawable.ic_album)))
                .build();
        result.add(new MediaBrowser.MediaItem(albums, MediaBrowser.MediaItem.FLAG_BROWSABLE));
        MediaDescription artists = new MediaDescription.Builder()
                .setMediaId(CONTENT_BROWSER_ARTISTS)
                .setTitle("Artists")
                .setIconUri(Uri.parse(mResourceUri + getApplicationContext().getResources()
                        .getResourceEntryName(R.drawable.ic_artist)))
                .build();
        result.add(new MediaBrowser.MediaItem(artists, MediaBrowser.MediaItem.FLAG_BROWSABLE));
        MediaDescription playlists = new MediaDescription.Builder()
                .setMediaId(CONTENT_BROWSER_PLAYLISTS)
                .setTitle("Playlists")
                .setIconUri(Uri.parse(mResourceUri + getApplicationContext().getResources()
                        .getResourceEntryName(R.drawable.ic_playlist)))
                .build();
        result.add(new MediaBrowser.MediaItem(playlists, MediaBrowser.MediaItem.FLAG_BROWSABLE));
        MediaDescription songs = new MediaDescription.Builder()
                .setMediaId(CONTENT_BROWSER_SONGS)
                .setTitle("Songs")
                .setIconUri(Uri.parse(mResourceUri + getApplicationContext().getResources()
                        .getResourceEntryName(R.drawable.ic_playlist)))
                .build();
        result.add(new MediaBrowser.MediaItem(songs, MediaBrowser.MediaItem.FLAG_BROWSABLE));
        mMediaIdToChildren.put(CONTENT_BROWSER_ROOT, result);
    }

    private List<MediaBrowser.MediaItem> setupAlbumRoot() {
        List<MediaBrowser.MediaItem> result = new ArrayList<>();
        ArrayList<Long> mediaIds = new ArrayList<>();
        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                new String[]{ AlbumColumns.ALBUM, AlbumColumns.ALBUM_ID },
                "(" + MediaStore.Audio.Media.IS_MUSIC + " !=0 )", null,
                MediaStore.Audio.Albums.DEFAULT_SORT_ORDER)) {
            while (c.moveToNext()) {
                String albumName = c.getString(
                        c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                long albumId = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                if (!mediaIds.contains(albumId)) {
                    mediaIds.add(albumId);
                }
                else {
                    continue;
                }
                Bitmap bitmap = mImageFetcher.getArtwork(albumName, albumId, true)
                        .getBitmap();
                MediaMetadata data = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, albumName)
                        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "album_" + albumId)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                                ContentUris.withAppendedId(Uri.parse(
                                                "content://media/external/audio/albumart"),
                                        albumId).toString())
                        .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
                        .build();
                result.add(new MediaBrowser.MediaItem(data.getDescription(),
                        MediaBrowser.MediaItem.FLAG_BROWSABLE));
            }
        }
        return result;
    }

    private List<MediaBrowser.MediaItem> setupArtistRoot() {
        List<MediaBrowser.MediaItem> result = new ArrayList<>();
        ArrayList<String> mediaIds = new ArrayList<>();
        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                new String[]{ MediaStore.Audio.ArtistColumns.ARTIST },
                "(" + MediaStore.Audio.Media.IS_MUSIC + " !=0 )", null,
                MediaStore.Audio.Artists.DEFAULT_SORT_ORDER)) {
            while (c.moveToNext()) {
                String artistName = c.getString(
                        c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                if (!mediaIds.contains(artistName)) {
                    mediaIds.add(artistName);
                }
                else {
                    continue;
                }
                // TODO: Get artist bitmap
                //Bitmap bitmap = mImageFetcher.getArtwork(artistName, albumId, true)
                        //.getBitmap();
                MediaMetadata data = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, artistName)
                        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "artist_" + artistName)
                        /*.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                                ContentUris.withAppendedId(Uri.parse(
                                                "content://media/external/audio/albumart"),
                                        albumId).toString())
                        .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)*/
                        .build();
                result.add(new MediaBrowser.MediaItem(data.getDescription(),
                        MediaBrowser.MediaItem.FLAG_BROWSABLE));
            }
        }
        return result;
    }

    private List<MediaBrowser.MediaItem> setupPlaylistRoot() {
        // TODO: Get available playlists
        return null;
    }

    private void setupSongRoot() {
        List<MediaBrowser.MediaItem> result = new ArrayList<>();
        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                PROJECTION, "(" + MediaStore.Audio.Media.IS_MUSIC + " !=0 )",
                null, MediaStore.Audio.Media.DEFAULT_SORT_ORDER)) {
            while (c.moveToNext()) {
                String albumName = c.getString(
                        c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                long albumId = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                String artistName = c.getString(
                        c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                if (!mMediaIdToChildren.containsKey("album_" + albumId)) {
                    mMediaIdToChildren.put("album_" + albumId, new ArrayList<>());
                }
                if (!mMediaIdToChildren.containsKey("artist_" + artistName)) {
                    mMediaIdToChildren.put("artist_" + artistName, new ArrayList<>());
                }
                Bitmap bitmap = mImageFetcher.getArtwork(albumName, albumId, true)
                        .getBitmap();
                long mediaId = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                MediaMetadata data = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, albumName)
                        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, String.valueOf(mediaId))
                        .putString(MediaMetadata.METADATA_KEY_TITLE, c.getString(
                                c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)))
                        .putString(MediaMetadata.METADATA_KEY_MEDIA_URI,
                                ContentUris.withAppendedId(
                                        MediaStore.Video.Media.getContentUri(
                                                MediaStore.VOLUME_EXTERNAL), mediaId)
                                        .toString())
                        .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                                ContentUris.withAppendedId(Uri.parse(
                                        "content://media/external/audio/albumart"),
                                        albumId).toString())
                        .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
                        .build();
                MediaBrowser.MediaItem item = new MediaBrowser.MediaItem(data.getDescription(),
                        MediaBrowser.MediaItem.FLAG_PLAYABLE);
                Objects.requireNonNull(mMediaIdToChildren.get("album_" + albumId)).add(item);
                Objects.requireNonNull(mMediaIdToChildren.get("artist_" + artistName)).add(item);
                result.add(item);
                mSongs.add(mediaId);
            }
        }
        mMediaIdToChildren.put(CONTENT_BROWSER_SONGS, result);
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String command = intent.getStringExtra(CMDNAME);

            if (AppWidgetSmall.APP_WIDGET_UPDATE.equals(command)) {
                final int[] small = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
                mAppWidgetSmall.performUpdate(MusicPlaybackService.this, small);
            } else if (AppWidgetLarge.APP_WIDGET_UPDATE.equals(command)) {
                final int[] large = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
                mAppWidgetLarge.performUpdate(MusicPlaybackService.this, large);
            } else if (AppWidgetLargeAlternate.APP_WIDGET_UPDATE.equals(command)) {
                final int[] largeAlt = intent
                        .getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
                mAppWidgetLargeAlternate.performUpdate(MusicPlaybackService.this, largeAlt);
            } else {
                handleCommandIntent(intent);
            }
        }
    };

    private ContentObserver mMediaStoreObserver;

    private class MediaStoreObserver extends ContentObserver implements Runnable {
        // milliseconds to delay before calling refresh to aggregate events
        private static final long REFRESH_DELAY = 500;
        private final Handler mHandler;

        public MediaStoreObserver(Handler handler) {
            super(handler);
            mHandler = handler;
        }

        @Override
        public void onChange(boolean selfChange) {
            // if a change is detected, remove any scheduled callback
            // then post a new one. This is intended to prevent closely
            // spaced events from generating multiple refresh calls
            mHandler.removeCallbacks(this);
            mHandler.postDelayed(this, REFRESH_DELAY);
        }

        @Override
        public void run() {
            // actually call refresh when the delayed callback fires
            Log.e("ELEVEN", "calling refresh!");
            refresh();
        }
    }

    private static final class MusicPlayerHandler extends Handler {
        private final WeakReference<MusicPlaybackService> mService;
        private float mCurrentVolume = 1.0f;

        private static final int DOUBLE_CLICK_TIMEOUT = 800;
        private int mHeadsetHookClickCounter = 0;

        /**
         * Constructor of <code>MusicPlayerHandler</code>
         *
         * @param service The service to use.
         * @param looper  The thread to run on.
         */
        public MusicPlayerHandler(final MusicPlaybackService service, final Looper looper) {
            super(looper);
            mService = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(@NonNull final Message msg) {
            final MusicPlaybackService service = mService.get();
            if (service == null) {
                return;
            }

            synchronized (service) {
                switch (msg.what) {
                    case FADEDOWN:
                        mCurrentVolume -= .05f;
                        if (mCurrentVolume > .2f) {
                            sendEmptyMessageDelayed(FADEDOWN, 10);
                        } else {
                            mCurrentVolume = .2f;
                        }
                        service.mPlayer.setVolume(mCurrentVolume);
                        break;
                    case FADEUP:
                        mCurrentVolume += .01f;
                        if (mCurrentVolume < 1.0f) {
                            sendEmptyMessageDelayed(FADEUP, 10);
                        } else {
                            mCurrentVolume = 1.0f;
                        }
                        service.mPlayer.setVolume(mCurrentVolume);
                        break;
                    case SERVER_DIED:
                        if (service.isPlaying()) {
                            final TrackErrorInfo info = (TrackErrorInfo) msg.obj;
                            service.sendErrorMessage(info.mTrackName);

                            // since the service isPlaying(), we only need to remove the offending
                            // audio track, and the code will automatically play the next track
                            service.removeTrack(info.mId);
                        } else {
                            service.openCurrentAndNext();
                        }
                        break;
                    case TRACK_WENT_TO_NEXT:
                        service.setAndRecordPlayPos(service.mNextPlayPos);
                        service.setNextTrack();
                        if (service.mCursor != null) {
                            service.mCursor.close();
                            service.mCursor = null;
                        }
                        service.updateCursor(service.mPlaylist.get(service.mPlayPos).mId);
                        service.notifyChange(META_CHANGED);
                        break;
                    case TRACK_ENDED:
                        if (service.mRepeatMode == REPEAT_CURRENT) {
                            service.seek(0);
                            service.play();
                        } else {
                            service.gotoNext(false);
                        }
                        break;
                    case LYRICS:
                        service.mLyrics = (String) msg.obj;
                        service.notifyChange(NEW_LYRICS);
                        break;
                    case HEADSET_HOOK_EVENT: {
                        long eventTime = (Long) msg.obj;

                        mHeadsetHookClickCounter = Math.min(mHeadsetHookClickCounter + 1, 3);
                        if (D) Log.d(TAG, "Got headset click, count = " + mHeadsetHookClickCounter);
                        removeMessages(HEADSET_HOOK_MULTI_CLICK_TIMEOUT);

                        if (mHeadsetHookClickCounter == 3) {
                            sendEmptyMessage(HEADSET_HOOK_MULTI_CLICK_TIMEOUT);
                        } else {
                            sendEmptyMessageAtTime(HEADSET_HOOK_MULTI_CLICK_TIMEOUT,
                                    eventTime + DOUBLE_CLICK_TIMEOUT);
                        }
                        break;
                    }
                    case HEADSET_HOOK_MULTI_CLICK_TIMEOUT:
                        if (D) Log.d(TAG, "Handling headset click");
                        switch (mHeadsetHookClickCounter) {
                            case 1:
                                service.togglePlayPause();
                                break;
                            case 2:
                                service.gotoNext(true);
                                break;
                            case 3:
                                service.prev(false);
                                break;
                        }
                        mHeadsetHookClickCounter = 0;
                        service.mHeadsetHookWakeLock.release();
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private static final class Shuffler {

        private final LinkedList<Integer> mHistoryOfNumbers = new LinkedList<>();

        private final TreeSet<Integer> mPreviousNumbers = new TreeSet<>();

        private final Random mRandom = new Random();

        private int mPrevious;

        /**
         * Constructor of <code>Shuffler</code>
         */
        public Shuffler() {
            super();
        }

        /**
         * @param interval The length the queue
         * @return The position of the next track to play
         */
        public int nextInt(final int interval) {
            int next;
            do {
                next = mRandom.nextInt(interval);
            } while (next == mPrevious && interval > 1 && !mPreviousNumbers.contains(next));
            mPrevious = next;
            mHistoryOfNumbers.add(mPrevious);
            mPreviousNumbers.add(mPrevious);
            cleanUpHistory();
            return next;
        }

        /**
         * Removes old tracks and cleans up the history preparing for new tracks
         * to be added to the mapping
         */
        private void cleanUpHistory() {
            if (!mHistoryOfNumbers.isEmpty() && mHistoryOfNumbers.size() >= MAX_HISTORY_SIZE) {
                for (int i = 0; i < Math.max(1, MAX_HISTORY_SIZE / 2); i++) {
                    mPreviousNumbers.remove(mHistoryOfNumbers.removeFirst());
                }
            }
        }
    }

    private static final class TrackErrorInfo {
        public final long mId;
        public final String mTrackName;

        public TrackErrorInfo(long id, String trackName) {
            mId = id;
            mTrackName = trackName;
        }
    }

    private static final class MultiPlayer implements MediaPlayer.OnErrorListener,
            MediaPlayer.OnCompletionListener {

        private final WeakReference<MusicPlaybackService> mService;

        private MediaPlayer mCurrentMediaPlayer = new MediaPlayer();

        private MediaPlayer mNextMediaPlayer;

        private final Handler mHandler;
        private final AudioAttributes mAudioAttributes;

        private boolean mIsInitialized = false;

        private SrtManager mSrtManager;

        private String mNextMediaPath;

        /**
         * Constructor of <code>MultiPlayer</code>
         */
        public MultiPlayer(final MusicPlaybackService service, final Handler handler,
                           final AudioAttributes attrs) {
            mService = new WeakReference<>(service);
            mHandler = handler;
            mAudioAttributes = attrs;
            mSrtManager = new SrtManager() {
                @Override
                public void onTimedText(String text) {
                    mHandler.obtainMessage(LYRICS, text).sendToTarget();
                }
            };
        }

        /**
         * @param path The path of the file, or the http/rtsp URL of the stream
         *             you want to play
         */
        public void setDataSource(final String path) {
            mIsInitialized = setDataSourceImpl(mCurrentMediaPlayer, path);
            if (mIsInitialized) {
                loadSrt(path);
                setNextDataSource(null);
            }
        }

        private void loadSrt(final String path) {
            mSrtManager.reset();

            Uri uri = Uri.parse(path);
            String filePath = null;

            if (path.startsWith("content://")) {
                // resolve the content resolver path to a file path
                Cursor cursor = null;
                try {
                    final String[] proj = {MediaStore.Audio.Media.DATA};
                    cursor = mService.get().getContentResolver().query(uri, proj,
                            null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        filePath = cursor.getString(0);
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            } else {
                filePath = uri.getPath();
            }

            if (filePath != null && !TextUtils.isEmpty(filePath)) {
                final int lastIndex = filePath.lastIndexOf('.');
                if (lastIndex != -1) {
                    String newPath = filePath.substring(0, lastIndex) + ".srt";
                    final File f = new File(newPath);

                    mSrtManager.initialize(mCurrentMediaPlayer, f);
                }
            }
        }

        /**
         * @param player The {@link MediaPlayer} to use
         * @param path   The path of the file, or the http/rtsp URL of the stream
         *               you want to play
         * @return True if the <code>player</code> has been prepared and is
         * ready to play, false otherwise
         */
        private boolean setDataSourceImpl(final MediaPlayer player, final String path) {
            try {
                player.reset();
                player.setOnPreparedListener(null);
                if (path.startsWith("content://")) {
                    player.setDataSource(mService.get(), Uri.parse(path));
                } else {
                    player.setDataSource(path);
                }
                player.setAudioAttributes(mAudioAttributes);
                player.prepare();
            } catch (final IOException | IllegalArgumentException todo) {
                // TODO: notify the user why the file couldn't be opened
                return false;
            }
            player.setOnCompletionListener(this);
            player.setOnErrorListener(this);
            return true;
        }

        /**
         * Set the MediaPlayer to start when this MediaPlayer finishes playback.
         *
         * @param path The path of the file, or the http/rtsp URL of the stream
         *             you want to play
         */
        public void setNextDataSource(final String path) {
            mNextMediaPath = null;
            try {
                mCurrentMediaPlayer.setNextMediaPlayer(null);
            } catch (IllegalArgumentException e) {
                Log.i(TAG, "Next media player is current one, continuing");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Media player not initialized!");
                return;
            }
            if (mNextMediaPlayer != null) {
                mNextMediaPlayer.release();
                mNextMediaPlayer = null;
            }
            if (path == null) {
                return;
            }
            mNextMediaPlayer = new MediaPlayer();
            mNextMediaPlayer.setAudioSessionId(getAudioSessionId());
            if (setDataSourceImpl(mNextMediaPlayer, path)) {
                mNextMediaPath = path;
                mCurrentMediaPlayer.setNextMediaPlayer(mNextMediaPlayer);
            } else {
                if (mNextMediaPlayer != null) {
                    mNextMediaPlayer.release();
                    mNextMediaPlayer = null;
                }
            }
        }

        /**
         * @return True if the player is ready to go, false otherwise
         */
        public boolean isInitialized() {
            return mIsInitialized;
        }

        /**
         * Starts or resumes playback.
         */
        public void start() {
            mCurrentMediaPlayer.start();
            mSrtManager.play();
        }

        /**
         * Resets the MediaPlayer to its uninitialized state.
         */
        public void stop() {
            mCurrentMediaPlayer.reset();
            mSrtManager.reset();
            mIsInitialized = false;
        }

        /**
         * Releases resources associated with this MediaPlayer object.
         */
        public void release() {
            mCurrentMediaPlayer.release();
            mSrtManager.release();
            mSrtManager = null;
        }

        /**
         * Pauses playback. Call start() to resume.
         */
        public void pause() {
            mCurrentMediaPlayer.pause();
            mSrtManager.pause();
        }

        /**
         * Gets the duration of the file.
         *
         * @return The duration in milliseconds
         */
        public long duration() {
            try {
                return mCurrentMediaPlayer.getDuration();
            } catch (IllegalStateException exc) {
                Log.e(TAG, "Could not get duration", exc);
            }
            return 0L;
        }

        /**
         * Gets the current playback position.
         *
         * @return The current position in milliseconds
         */
        public long position() {
            try {
                return mCurrentMediaPlayer.getCurrentPosition();
            } catch (IllegalStateException exc) {
                Log.e(TAG, "Could not get current position", exc);
            }
            return 0L;
        }

        /**
         * Gets the current playback position.
         *
         * @param whereto The offset in milliseconds from the start to seek to
         * @return The offset in milliseconds from the start to seek to
         */
        public long seek(final long whereto) {
            mCurrentMediaPlayer.seekTo((int) whereto);
            mSrtManager.seekTo(whereto);
            return whereto;
        }

        /**
         * Sets the volume on this player.
         *
         * @param vol Left and right volume scalar
         */
        public void setVolume(final float vol) {
            mCurrentMediaPlayer.setVolume(vol, vol);
        }

        /**
         * Returns the audio session ID.
         *
         * @return The current audio session ID.
         */
        public int getAudioSessionId() {
            return mCurrentMediaPlayer.getAudioSessionId();
        }

        @Override
        public boolean onError(final MediaPlayer mp, final int what, final int extra) {
            Log.w(TAG, "Music Server Error what: " + what + " extra: " + extra);
            if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
                final MusicPlaybackService service = mService.get();
                if (service == null) {
                    return false;
                }
                final TrackErrorInfo errorInfo = new TrackErrorInfo(service.getAudioId(),
                        service.getTrackName());

                mIsInitialized = false;
                mCurrentMediaPlayer.release();
                mCurrentMediaPlayer = new MediaPlayer();
                Message msg = mHandler.obtainMessage(SERVER_DIED, errorInfo);
                mHandler.sendMessageDelayed(msg, 2000);
                return true;
            }
            return false;
        }

        @Override
        public void onCompletion(final MediaPlayer mp) {
            if (mp == mCurrentMediaPlayer && mNextMediaPlayer != null) {
                mCurrentMediaPlayer.release();
                mCurrentMediaPlayer = mNextMediaPlayer;
                loadSrt(mNextMediaPath);
                mNextMediaPath = null;
                mNextMediaPlayer = null;
                mHandler.sendEmptyMessage(TRACK_WENT_TO_NEXT);
            } else {
                mHandler.sendEmptyMessage(TRACK_ENDED);
            }
        }
    }

    @SuppressWarnings("unused")
    private static final class ServiceStub extends IElevenService.Stub {

        private final WeakReference<MusicPlaybackService> mService;

        private ServiceStub(final MusicPlaybackService service) {
            mService = new WeakReference<>(service);
        }

        @Override
        public void openFile(final String path) {
            mService.get().openFile(path);
        }

        @Override
        public void open(final long[] list, final int position, long sourceId, int sourceType) {
            mService.get().open(list, position, sourceId, IdType.getTypeById(sourceType));
        }

        @Override
        public void stop() {
            mService.get().stop();
        }

        @Override
        public void pause() {
            mService.get().pause(false);
        }

        @Override
        public void play() {
            mService.get().play();
        }

        @Override
        public void prev(boolean forcePrevious) {
            mService.get().prev(forcePrevious);
        }

        @Override
        public void next() {
            mService.get().gotoNext(true);
        }

        @Override
        public void enqueue(final long[] list, final int action, long sourceId, int sourceType) {
            mService.get().enqueue(list, action, sourceId, IdType.getTypeById(sourceType));
        }

        @Override
        public void setQueuePosition(final int index) {
            mService.get().setQueuePosition(index);
        }

        @Override
        public void setShuffleMode(final int shufflemode) {
            mService.get().setShuffleMode(shufflemode);
        }

        @Override
        public void setRepeatMode(final int repeatmode) {
            mService.get().setRepeatMode(repeatmode);
        }

        @Override
        public void moveQueueItem(final int from, final int to) {
            mService.get().moveQueueItem(from, to);
        }

        @Override
        public void refresh() {
            mService.get().refresh();
        }

        @Override
        public void playlistChanged() {
            mService.get().playlistChanged();
        }

        @Override
        public boolean isPlaying() {
            return mService.get().isPlaying();
        }

        @Override
        public long[] getQueue() {
            return mService.get().getQueue();
        }

        @Override
        public long getQueueItemAtPosition(int position) {
            return mService.get().getQueueItemAtPosition(position);
        }

        @Override
        public int getQueueSize() {
            return mService.get().getQueueSize();
        }

        @Override
        public int getQueueHistoryPosition(int position) {
            return mService.get().getQueueHistoryPosition(position);
        }

        @Override
        public int getQueueHistorySize() {
            return mService.get().getQueueHistorySize();
        }

        @Override
        public int[] getQueueHistoryList() {
            return mService.get().getQueueHistoryList();
        }

        @Override
        public long duration() {
            return mService.get().duration();
        }

        @Override
        public long position() {
            return mService.get().position();
        }

        @Override
        public long seek(final long position) {
            return mService.get().seek(position);
        }

        @Override
        public void seekRelative(final long deltaInMs) {
            mService.get().seekRelative(deltaInMs);
        }

        @Override
        public long getAudioId() {
            return mService.get().getAudioId();
        }

        @Override
        public MusicPlaybackTrack getCurrentTrack() {
            return mService.get().getCurrentTrack();
        }

        @Override
        public MusicPlaybackTrack getTrack(int index) {
            return mService.get().getTrack(index);
        }

        @Override
        public long getNextAudioId() {
            return mService.get().getNextAudioId();
        }

        @Override
        public long getPreviousAudioId() {
            return mService.get().getPreviousAudioId();
        }

        @Override
        public long getArtistId() {
            return mService.get().getArtistId();
        }

        @Override
        public long getAlbumId() {
            return mService.get().getAlbumId();
        }

        @Override
        public String getArtistName() {
            return mService.get().getArtistName();
        }

        @Override
        public String getTrackName() {
            return mService.get().getTrackName();
        }

        @Override
        public String getAlbumName() {
            return mService.get().getAlbumName();
        }

        @Override
        public String getPath() {
            return mService.get().getPath();
        }

        @Override
        public int getQueuePosition() {
            return mService.get().getQueuePosition();
        }

        @Override
        public int getShuffleMode() {
            return mService.get().getShuffleMode();
        }

        @Override
        public int getRepeatMode() {
            return mService.get().getRepeatMode();
        }

        @Override
        public int removeTracks(final int first, final int last) {
            return mService.get().removeTracks(first, last);
        }

        @Override
        public int removeTrack(final long id) {
            return mService.get().removeTrack(id);
        }

        @Override
        public boolean removeTrackAtPosition(final long id, final int position) {
            return mService.get().removeTrackAtPosition(id, position);
        }

        @Override
        public int getMediaMountedCount() {
            return mService.get().getMediaMountedCount();
        }

        @Override
        public int getAudioSessionId() {
            return mService.get().getAudioSessionId();
        }

        @Override
        public void setShakeToPlayEnabled(boolean enabled) {
            mService.get().setShakeToPlayEnabled(enabled);
        }
    }

    private class QueueUpdateTask {
        private final long[] mQueue;

        private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

        public QueueUpdateTask(long[] queue) {
            mQueue = queue != null ? Arrays.copyOf(queue, queue.length) : null;
        }

        public void execute() {
            Handler handler = new Handler(Looper.getMainLooper());

            mExecutor.execute(() -> {
                List<MediaSession.QueueItem> items = getQueuedItems();

                handler.post(() -> {
                    mSession.setQueue(items);
                });
            });
        }

        public void cancel() {
            mExecutor.shutdownNow();
        }

        private List<MediaSession.QueueItem> getQueuedItems() {
            if (mQueue == null || mQueue.length == 0) {
                return null;
            }

            final StringBuilder selection = new StringBuilder();
            selection.append(MediaStore.Audio.Media._ID).append(" IN (");
            for (int i = 0; i < mQueue.length; i++) {
                if (i != 0) {
                    selection.append(",");
                }
                selection.append(mQueue[i]);
            }
            selection.append(")");

            Cursor c = getContentResolver().query(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    new String[]{AudioColumns._ID, AudioColumns.TITLE, AudioColumns.ARTIST},
                    selection.toString(), null, null);
            if (c == null) {
                return null;
            }

            try {
                LongSparseArray<MediaDescription> descsById = new LongSparseArray<>();
                final int idColumnIndex = c.getColumnIndexOrThrow(AudioColumns._ID);
                final int titleColumnIndex = c.getColumnIndexOrThrow(AudioColumns.TITLE);
                final int artistColumnIndex = c.getColumnIndexOrThrow(AudioColumns.ARTIST);

                while (c.moveToNext()) {
                    final MediaDescription desc = new MediaDescription.Builder()
                            .setTitle(c.getString(titleColumnIndex))
                            .setSubtitle(c.getString(artistColumnIndex))
                            .build();
                    final long id = c.getLong(idColumnIndex);
                    descsById.put(id, desc);
                }

                List<MediaSession.QueueItem> items = new ArrayList<>();
                for (int i = 0; i < mQueue.length; i++) {
                    MediaDescription desc = descsById.get(mQueue[i]);
                    if (desc == null) {
                        // shouldn't happen except in corner cases like
                        // music being deleted while we were processing
                        desc = new MediaDescription.Builder().build();
                    }
                    items.add(new MediaSession.QueueItem(desc, i));
                }
                return items;
            } finally {
                c.close();
            }
        }
    }
}
