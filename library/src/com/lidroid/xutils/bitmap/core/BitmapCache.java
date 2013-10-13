/*
 * Copyright (c) 2013. wyouflf (wyouflf@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lidroid.xutils.bitmap.core;

import android.graphics.Bitmap;
import com.lidroid.xutils.bitmap.BitmapDisplayConfig;
import com.lidroid.xutils.bitmap.BitmapGlobalConfig;
import com.lidroid.xutils.util.IOUtils;
import com.lidroid.xutils.util.LogUtils;
import com.lidroid.xutils.util.core.LruDiskCache;
import com.lidroid.xutils.util.core.LruMemoryCache;

import java.io.*;
import java.lang.ref.SoftReference;


public class BitmapCache {

    private final int DISK_CACHE_INDEX = 0;

    private LruDiskCache mDiskLruCache;
    private LruMemoryCache<String, SoftReference<Bitmap>> mMemoryCache;

    private final Object mDiskCacheLock = new Object();
    private boolean isDiskCacheReadied = false;

    private BitmapGlobalConfig globalConfig;

    /**
     * Creating a new ImageCache object using the specified parameters.
     *
     * @param config The cache parameters to use to initialize the cache
     */
    public BitmapCache(BitmapGlobalConfig config) {
        this.globalConfig = config;
    }


    /**
     * Initialize the memory cache
     */
    public void initMemoryCache() {
        if (!globalConfig.isMemoryCacheEnabled()) return;

        // Set up memory cache
        if (mMemoryCache != null) {
            try {
                clearMemoryCache();
            } catch (Exception e) {
            }
        }
        mMemoryCache = new LruMemoryCache<String, SoftReference<Bitmap>>(globalConfig.getMemoryCacheSize()) {
            /**
             * Measure item size in bytes rather than units which is more practical
             * for a bitmap cache
             */
            @Override
            protected int sizeOf(String key, SoftReference<Bitmap> bitmapRef) {
                return BitmapCommonUtils.getBitmapSize(bitmapRef.get());
            }
        };
    }

    /**
     * Initializes the disk cache.  Note that this includes disk access so this should not be
     * executed on the main/UI thread. By default an ImageCache does not initialize the disk
     * cache when it is created, instead you should call initDiskCache() to initialize it on a
     * background thread.
     */
    public void initDiskCache() {
        if (!globalConfig.isDiskCacheEnabled()) return;

        // Set up disk cache
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache == null || mDiskLruCache.isClosed()) {
                File diskCacheDir = new File(globalConfig.getDiskCachePath());
                if (!diskCacheDir.exists()) {
                    diskCacheDir.mkdirs();
                }
                long availableSpace = BitmapCommonUtils.getAvailableSpace(diskCacheDir);
                long diskCacheSize = globalConfig.getDiskCacheSize();
                diskCacheSize = availableSpace > diskCacheSize ? diskCacheSize : availableSpace;
                try {
                    mDiskLruCache = LruDiskCache.open(diskCacheDir, 1, 1, diskCacheSize);
                    mDiskLruCache.setDiskCacheFileNameGenerator(globalConfig.getDiskCacheFileNameGenerator());
                } catch (Exception e) {
                    mDiskLruCache = null;
                    LogUtils.e(e.getMessage(), e);
                }
            }
            isDiskCacheReadied = true;
            mDiskCacheLock.notifyAll();
        }
    }

    public void setMemoryCacheSize(int maxSize) {
        if (mMemoryCache != null) {
            mMemoryCache.setMaxSize(maxSize);
        }
    }

    public void setDiskCacheSize(int maxSize) {
        if (mDiskLruCache != null) {
            mDiskLruCache.setMaxSize(maxSize);
        }
    }

    public void setDiskCacheFileNameGenerator(LruDiskCache.DiskCacheFileNameGenerator diskCacheFileNameGenerator) {
        if (mDiskLruCache != null) {
            mDiskLruCache.setDiskCacheFileNameGenerator(diskCacheFileNameGenerator);
        }
    }

    public Bitmap downloadBitmap(String uri, BitmapDisplayConfig config) {

        BitmapMeta bitmapMeta = new BitmapMeta();

        OutputStream outputStream = null;
        LruDiskCache.Snapshot snapshot = null;

        try {

            // download to disk cache
            if (globalConfig.isDiskCacheEnabled()) {
                synchronized (mDiskCacheLock) {
                    // Wait for disk cache to initialize
                    while (!isDiskCacheReadied) {
                        try {
                            mDiskCacheLock.wait();
                        } catch (InterruptedException e) {
                        }
                    }

                    if (mDiskLruCache != null) {
                        snapshot = mDiskLruCache.get(uri);
                        if (snapshot == null) {
                            LruDiskCache.Editor editor = mDiskLruCache.edit(uri);
                            if (editor != null) {
                                outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
                                bitmapMeta.expiryTimestamp = globalConfig.getDownloader().downloadToStream(uri, outputStream);
                                if (bitmapMeta.expiryTimestamp < 0) {
                                    editor.abort();
                                    return null;
                                } else {
                                    editor.setEntryExpiryTimestamp(bitmapMeta.expiryTimestamp);
                                    editor.commit();
                                }
                                snapshot = mDiskLruCache.get(uri);
                            }
                        }
                        if (snapshot != null) {
                            bitmapMeta.inputStream = snapshot.getInputStream(DISK_CACHE_INDEX);
                        }
                    }
                }
            }

            if (!globalConfig.isDiskCacheEnabled() || mDiskLruCache == null || bitmapMeta.inputStream == null) {
                outputStream = new ByteArrayOutputStream();
                bitmapMeta.expiryTimestamp = globalConfig.getDownloader().downloadToStream(uri, outputStream);
                if (bitmapMeta.expiryTimestamp < 0) {
                    return null;
                } else {
                    bitmapMeta.data = ((ByteArrayOutputStream) outputStream).toByteArray();
                }
            }

            return addBitmapToMemoryCache(uri, config, bitmapMeta);
        } catch (Exception e) {
            LogUtils.e(e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(outputStream);
            IOUtils.closeQuietly(snapshot);
        }

        return null;
    }

    /**
     * Add a bitmap to memory cache, if the cache not contains the uri.
     *
     * @param uri        Unique identifier for the bitmap to store
     * @param config
     * @param bitmapMeta The bitmap and expiry timestamp to store
     */
    private Bitmap addBitmapToMemoryCache(String uri, BitmapDisplayConfig config, BitmapMeta bitmapMeta) throws IOException {
        if (uri == null || bitmapMeta == null) {
            return null;
        }

        Bitmap bitmap = null;
        if (bitmapMeta.inputStream != null) {
            if (config.isShowOriginal()) {
                bitmap = BitmapDecoder.decodeFileDescriptor(bitmapMeta.inputStream.getFD());
            } else {
                bitmap = BitmapDecoder.decodeSampledBitmapFromDescriptor(
                        bitmapMeta.inputStream.getFD(),
                        config.getBitmapMaxWidth(),
                        config.getBitmapMaxHeight(),
                        config.getBitmapConfig());
            }
        } else if (bitmapMeta.data != null) {
            if (config.isShowOriginal()) {
                bitmap = BitmapDecoder.decodeByteArray(bitmapMeta.data);
            } else {
                bitmap = BitmapDecoder.decodeSampledBitmapFromByteArray(
                        bitmapMeta.data,
                        config.getBitmapMaxWidth(),
                        config.getBitmapMaxHeight(),
                        config.getBitmapConfig());
            }
        } else {
            return null;
        }

        if (bitmap == null) {
            return null;
        }

        // add to memory cache
        String key = uri + config.toString();
        if (globalConfig.isMemoryCacheEnabled() && mMemoryCache != null) {
            mMemoryCache.put(key, new SoftReference<Bitmap>(bitmap), bitmapMeta.expiryTimestamp);
        }

        return bitmap;
    }

    /**
     * Get the bitmap from memory cache.
     *
     * @param uri    Unique identifier for which item to get
     * @param config
     * @return The bitmap if found in cache, null otherwise
     */
    public Bitmap getBitmapFromMemCache(String uri, BitmapDisplayConfig config) {
        String key = uri + config.toString();
        if (mMemoryCache != null) {
            SoftReference<Bitmap> softRef = mMemoryCache.get(key);
            return softRef == null ? null : softRef.get();
        }
        return null;
    }

    /**
     * Get the bitmap file from disk cache.
     *
     * @param uri Unique identifier for which item to get
     * @return The file if found in cache.
     */
    public File getBitmapFileFromDiskCache(String uri) {
        if (mDiskLruCache != null) {
            return mDiskLruCache.getCacheFile(uri, DISK_CACHE_INDEX);
        }
        return null;
    }

    /**
     * Get the bitmap from disk cache.
     *
     * @param uri
     * @param config
     * @return
     */
    public Bitmap getBitmapFromDiskCache(String uri, BitmapDisplayConfig config) {
        if (!globalConfig.isDiskCacheEnabled()) return null;
        synchronized (mDiskCacheLock) {
            while (!isDiskCacheReadied) {
                try {
                    mDiskCacheLock.wait();
                } catch (InterruptedException e) {
                }
            }
            if (mDiskLruCache != null) {
                LruDiskCache.Snapshot snapshot = null;
                try {
                    snapshot = mDiskLruCache.get(uri);
                    if (snapshot != null) {
                        Bitmap bitmap = null;
                        if (config.isShowOriginal()) {
                            bitmap = BitmapDecoder.decodeFileDescriptor(
                                    snapshot.getInputStream(DISK_CACHE_INDEX).getFD());
                        } else {
                            bitmap = BitmapDecoder.decodeSampledBitmapFromDescriptor(
                                    snapshot.getInputStream(DISK_CACHE_INDEX).getFD(),
                                    config.getBitmapMaxWidth(),
                                    config.getBitmapMaxHeight(),
                                    config.getBitmapConfig());
                        }

                        // add to memory cache
                        String key = uri + config.toString();
                        if (globalConfig.isMemoryCacheEnabled() && mMemoryCache != null && bitmap != null) {
                            mMemoryCache.put(key, new SoftReference<Bitmap>(bitmap), mDiskLruCache.getExpiryTimestamp(uri));
                        }

                        return bitmap;
                    }
                } catch (Exception e) {
                    LogUtils.e(e.getMessage(), e);
                } finally {
                    IOUtils.closeQuietly(snapshot);
                }
            }
            return null;
        }
    }

    /**
     * Clears both the memory and disk cache associated with this ImageCache object. Note that
     * this includes disk access so this should not be executed on the main/UI thread.
     */
    public void clearCache() {
        clearMemoryCache();
        clearDiskCache();
    }

    public void clearMemoryCache() {
        if (mMemoryCache != null) {
            mMemoryCache.evictAll();
        }
    }

    public void clearDiskCache() {
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null && !mDiskLruCache.isClosed()) {
                try {
                    mDiskLruCache.delete();
                } catch (Exception e) {
                    LogUtils.e(e.getMessage(), e);
                }
                mDiskLruCache = null;
                isDiskCacheReadied = false;
            }
        }
        initDiskCache();
    }


    public void clearCache(String uri, BitmapDisplayConfig config) {
        clearMemoryCache(uri, config);
        clearDiskCache(uri);
    }

    public void clearMemoryCache(String uri, BitmapDisplayConfig config) {
        String key = uri + config.toString();
        if (mMemoryCache != null) {
            mMemoryCache.remove(key);
        }
    }

    public void clearDiskCache(String uri) {
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null && !mDiskLruCache.isClosed()) {
                try {
                    mDiskLruCache.remove(uri);
                } catch (Exception e) {
                    LogUtils.e(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Flushes the disk cache associated with this ImageCache object. Note that this includes
     * disk access so this should not be executed on the main/UI thread.
     */
    public void flush() {
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null) {
                try {
                    mDiskLruCache.flush();
                } catch (Exception e) {
                    LogUtils.e(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Closes the disk cache associated with this ImageCache object. Note that this includes
     * disk access so this should not be executed on the main/UI thread.
     */
    public void close() {
        clearMemoryCache();
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null) {
                try {
                    if (!mDiskLruCache.isClosed()) {
                        mDiskLruCache.close();
                        mDiskLruCache = null;
                    }
                } catch (Exception e) {
                    LogUtils.e(e.getMessage(), e);
                }
            }
        }
    }

    private class BitmapMeta {
        public FileInputStream inputStream;
        public byte[] data;
        public long expiryTimestamp;
    }
}
