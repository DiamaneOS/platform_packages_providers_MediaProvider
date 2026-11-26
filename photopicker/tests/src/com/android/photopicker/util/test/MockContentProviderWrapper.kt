/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.util.test

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.provider.CloudMediaProviderContract
import android.test.mock.MockContentProvider
import com.android.photopicker.core.glide.PhotopickerMediaFetcher
import kotlin.concurrent.thread

/**
 * A small wrapper around MockContentProvider.
 *
 * This will be used to wrap [ContentResolver] to intercept calls to it and re-route them to the
 * internal mock this class holds.
 *
 * All not overridden / unimplemented methods will throw [UnsupportedOperationException] by the
 * underlying class.
 */
class MockContentProviderWrapper(val provider: ContentProvider) : MockContentProvider() {

    companion object {
        val AUTHORITY = "MOCK_CONTENT_PROVIDER"
    }

    /** Pass calls to the wrapped provider. */
    override fun openTypedAssetFile(
        uri: Uri,
        mimetype: String,
        opts: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): AssetFileDescriptor? {
        return provider.openTypedAssetFile(uri, mimetype, opts, cancellationSignal)
    }

    /** Pass calls to the wrapped provider. */
    override fun openTypedAssetFile(
        uri: Uri,
        mimeType: String,
        opts: Bundle?,
    ): AssetFileDescriptor? {
        return provider.openTypedAssetFile(uri, mimeType, opts)
    }

    /**
     * Pass calls to the wrapped provider. DiamaneOS: the picker's image call is answered as
     * MediaProvider answers it, with the wrapped provider's own file: MediaProvider opens the item
     * with these options and writes a PNG of the pixels its decode service checked into the pipe.
     * Here the test image goes into the pipe as it is.
     */
    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != PhotopickerMediaFetcher.PICKER_IMAGE_CALL || arg == null || extras == null) {
            return provider.call(authority, method, arg, extras)
        }
        // A copy, as binder makes one: the picker closes its own end as soon as the call returns.
        val parcel = Parcel.obtain()
        val received =
            try {
                extras.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                Bundle.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        @Suppress("DEPRECATION")
        val out =
            received.getParcelable<ParcelFileDescriptor>(
                PhotopickerMediaFetcher.EXTRA_PICKER_IMAGE_OUT
            ) ?: return provider.call(authority, method, arg, extras)
        val opts =
            Bundle().apply {
                @Suppress("DEPRECATION")
                extras.getParcelable<Point>(ContentResolver.EXTRA_SIZE)?.let {
                    putParcelable(ContentResolver.EXTRA_SIZE, it)
                }
                putBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, true)
                if (extras.getBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB)) {
                    putBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB, true)
                }
            }
        val mime =
            extras.getString(PhotopickerMediaFetcher.EXTRA_PICKER_IMAGE_MIME) ?: "image/*"
        val afd = provider.openTypedAssetFile(Uri.parse(arg), mime, opts, CancellationSignal())
        thread {
            ParcelFileDescriptor.AutoCloseOutputStream(out).use { sink ->
                afd?.createInputStream()?.use { it.copyTo(sink) }
            }
        }
        return Bundle()
    }
}
