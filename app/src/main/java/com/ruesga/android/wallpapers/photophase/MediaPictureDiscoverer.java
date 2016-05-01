/*
 * Copyright (C) 2015 Jorge Ruesga
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

package com.ruesga.android.wallpapers.photophase;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.AsyncTask.Status;
import android.provider.MediaStore;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.ruesga.android.wallpapers.photophase.preferences.PreferencesProvider.Preferences;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A class that load asynchronously the paths of all media stored in the device.
 * This class only seek at the specified paths
 */
public class MediaPictureDiscoverer {

    private static final String TAG = "MediaPictureDiscoverer";

    private static final boolean DEBUG = false;

    /**
     * An interface that is called when new data is ready.
     */
    public interface OnMediaPictureDiscoveredListener  {
        /**
         * Called when the starting to fetch data
         *
         * @param userRequest If the user requested this media discovery
         */
        void onStartMediaDiscovered(boolean userRequest);
        /**
         * Called when the all the data is ready
         *
         * @param images All the images paths found
         * @param userRequest If the user requested this media discovery
         */
        void onEndMediaDiscovered(File[] images, boolean userRequest);
        /**
         * Called when the partial data is ready
         *
         * @param images All the images paths found
         * @param userRequest If the user requested this media discovery
         */
        void onPartialMediaDiscovered(File[] images, boolean userRequest);
    }

    /**
     * The asynchronous task for query the MediaStore
     */
    private class AsyncDiscoverTask extends AsyncTask<Void, File, List<File>> {

        private final ContentResolver mFinalContentResolver;
        private final OnMediaPictureDiscoveredListener mFinalCallback;
        private final Set<String> mFilter;
        private final Set<String> mLastAlbums;
        private final Set<String> mNewAlbums;
        private final boolean mIsAutoSelectNewAlbums;
        private final boolean mUserRequest;

        /**
         * Constructor of <code>AsyncDiscoverTask</code>
         *
         * @param cr The {@link ContentResolver}
         * @param cb The {@link OnMediaPictureDiscoveredListener} listener
         * @param userRequest If the request was generated by the user
         */
        public AsyncDiscoverTask(
                ContentResolver cr, OnMediaPictureDiscoveredListener cb, boolean userRequest) {
            super();
            mFinalContentResolver = cr;
            mFinalCallback = cb;
            mFilter = Preferences.Media.getSelectedMedia();
            mLastAlbums = Preferences.Media.getLastDiscorevedAlbums();
            mIsAutoSelectNewAlbums = Preferences.Media.isAutoSelectNewAlbums();
            mNewAlbums = new HashSet<>();
            mUserRequest = userRequest;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected List<File> doInBackground(Void...params) {
            try {
                // Start progress
                publishProgress();

                // The columns to read
                final String[] projection = {MediaStore.MediaColumns.DATA};

                // Query external content
                List<File> paths =
                        getPictures(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                projection,
                                null,
                                null);
                if (DEBUG) {
                    int cc = paths.size();
                    Log.v(TAG, "Pictures found (" + cc + "):");
                    for (int i = 0; i < cc; i++) {
                        Log.v(TAG, "\t" + paths.get(i));
                    }
                }
                return paths;

            } catch (Exception e) {
                Log.e(TAG, "AsyncDiscoverTask failed.", e);

                // Return and empty list
                return new ArrayList<>();
            } finally {
                // Save the filter (could have new albums)
                Preferences.Media.setSelectedMedia(mContext, mFilter);
                Preferences.Media.setLastDiscorevedAlbums(mContext, mNewAlbums);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void onProgressUpdate(File... values) {
            if (mFinalCallback != null) {
                if (values == null || values.length == 0) {
                    mFinalCallback.onStartMediaDiscovered(mUserRequest);
                } else {
                    mFinalCallback.onPartialMediaDiscovered(values, mUserRequest);
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void onPostExecute(List<File> result) {
            if (mFinalCallback != null) {
                mFinalCallback.onEndMediaDiscovered(result.toArray(new File[result.size()]), mUserRequest);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void onCancelled(List<File> result) {
            // Nothing found
            if (mFinalCallback != null) {
                // Overwrite the user request setting. If the task is cancelled then
                // there is no notification to send to the user
                mFinalCallback.onEndMediaDiscovered(new File[]{}, false);
            }
        }

        /**
         * Method that return all the media store pictures for the content uri
         *
         * @param uri The content uri where to search
         * @param projection The field data to return
         * @param where A filter
         * @param args The filter arguments
         * @return List<File> The pictures found
         */
        private List<File> getPictures(
                Uri uri, String[] projection, String where, String[] args) {
            long start = System.currentTimeMillis();
            List<File> paths = new ArrayList<>();
            List<File> partial = new ArrayList<>();
            Cursor c = mFinalContentResolver.query(uri, projection, where, args, null);
            if (c != null) {
                try {
                    int i = 0;
                    while (c.moveToNext()) {
                        // Only valid files (those i can read)
                        String p = c.getString(0);
                        if (p != null) {
                            File f = new File(p);
                            catalog(f);

                            // Check if is a valid filter
                            if (matchFilter(f)) {
                                paths.add(f);
                                partial.add(f);
                            }
                        }

                        // Publish partial data
                        if (i % 5 == 0 && partial.size() > 0) {
                            publishProgress(partial.toArray(new File[partial.size()]));
                            partial.clear();
                        }
                        i++;
                    }
                } finally {
                    try {
                        c.close();
                    } catch (Exception e) {
                        // Ignore: handle exception
                    }
                }
            }
            long end = System.currentTimeMillis();
            if (DEBUG) Log.v(TAG, "Media reloaded in " + (end - start) + " miliseconds");
            return paths;
        }

        /**
         * Method that checks if the picture match the preferences filter
         *
         * @param picture The picture to check
         * @return boolean whether the picture match the filter
         */
        private boolean matchFilter(File picture) {
            return mFilter.contains(picture.getAbsolutePath()) ||
                    mFilter.contains(picture.getParentFile().getAbsolutePath());
        }

        /**
         * Method that catalog the file (set its album and determine if is a new album)
         *
         * @param f The file to catalog
         */
        private void catalog(File f) {
            File parent = f.getParentFile();
            String albumPath = parent.getAbsolutePath();

            // Add to new albums
            mNewAlbums.add(albumPath);

            // Is a new album?
            if (!mLastAlbums.contains(albumPath)) {
                // Is in the filter?
                if (mIsAutoSelectNewAlbums && !mFilter.contains(albumPath)) {
                    // Add the album to the selected filter
                    mFilter.add(parent.getAbsolutePath());
                }
            }
        }
    }

    private final Context mContext;
    private final OnMediaPictureDiscoveredListener mCallback;

    private AsyncDiscoverTask mTask;

    /**
     * Constructor of <code>MediaPictureDiscoverer</code>.
     *
     * @param ctx The current context
     * @param callback A callback to returns the data when it gets ready
     */
    public MediaPictureDiscoverer(Context ctx, OnMediaPictureDiscoveredListener callback) {
        super();
        mContext = ctx;
        mCallback = callback;
    }

    /**
     * Method that request a new reload of the media store picture data.
     *
     * @param userRequest If the request was generated by the user
     */
    public synchronized void discover(boolean userRequest) {
        if (mTask != null && mTask.getStatus().compareTo(Status.FINISHED) != 0 &&
                !mTask.isCancelled()) {
            mTask.cancel(true);
            mTask = null;
        }

        if (AndroidHelper.hasReadExternalStoragePermissionGranted(mContext)) {
            mTask = new AsyncDiscoverTask(mContext.getContentResolver(), mCallback, userRequest);
            mTask.execute();
        } else {
            // Notify that we don't have any files
            mCallback.onEndMediaDiscovered(new File[0], userRequest);
        }
    }

    /**
     * Method that destroy the references of this class
     */
    public void recycle() {
        if (mTask != null && !mTask.isCancelled()) {
            mTask.cancel(true);
        }
    }

}
