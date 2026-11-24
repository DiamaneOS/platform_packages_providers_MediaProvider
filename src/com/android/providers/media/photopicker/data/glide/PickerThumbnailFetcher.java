/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker.data.glide;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Point;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract;
import com.android.providers.media.vmdecode.frames.PickerImage;
import com.android.providers.media.vmdecode.frames.PickerImageClient;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;
import java.io.IOException;

/** Fetches checked pixels through the selected profile's MediaProvider. */
public class PickerThumbnailFetcher implements DataFetcher<PickerImage> {
    private final Context mContext;
    private final GlideLoadable mModel;
    private final int mWidth;
    private final int mHeight;
    private final boolean mIsThumbRequest;
    private final boolean mAnimate;
    private final PickerImageClient mClient = new PickerImageClient();
    private PickerImage mImage;

    PickerThumbnailFetcher(Context context, GlideLoadable model, int width, int height,
            boolean isThumbRequest, boolean animate) {
        mContext = context;
        mModel = model;
        mWidth = width;
        mHeight = height;
        mIsThumbRequest = isThumbRequest;
        mAnimate = animate;
    }

    @Override
    public void loadData(Priority priority, DataCallback<? super PickerImage> callback) {
        final Bundle options = new Bundle();
        options.putParcelable(ContentResolver.EXTRA_SIZE, new Point(mWidth, mHeight));
        options.putBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB, mIsThumbRequest);
        options.putString(PickerImageClient.MIME, "image/*");
        options.putBoolean(PickerImageClient.FRAMES, mAnimate);
        try {
            mImage = mClient.load(mContext.getContentResolver(), mModel.getLoadableUri(), options);
            callback.onDataReady(mImage);
        } catch (IOException e) {
            callback.onLoadFailed(e);
        }
    }

    @Override
    public void cleanup() {
        mClient.close();
        if (mImage != null) {
            try { mImage.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public void cancel() { mClient.close(); }
    @Override
    public Class<PickerImage> getDataClass() { return PickerImage.class; }
    @Override
    public DataSource getDataSource() { return DataSource.LOCAL; }
}
