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
import android.graphics.BitmapFactory
import android.os.ResultReceiver
import com.android.providers.media.vmdecode.frames.PickerImageClient
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.provider.CloudMediaProviderContract
import android.test.mock.MockContentProvider
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

    /** Tests only: hand the fixture's pixels through the production typed-result protocol. */
    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != PickerImageClient.METHOD || arg == null || extras == null) {
            return provider.call(authority, method, arg, extras)
        }
        val parcel = Parcel.obtain()
        val received = try {
            extras.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        @Suppress("DEPRECATION")
        val out = received.getParcelable<ParcelFileDescriptor>(PickerImageClient.OUT) ?: error("No liveness fd")
        @Suppress("DEPRECATION")
        val receiver = received.getParcelable<ResultReceiver>(PickerImageClient.RECEIVER) ?: error("No receiver")
        val opts = Bundle().apply {
            @Suppress("DEPRECATION")
            extras.getParcelable<Point>(ContentResolver.EXTRA_SIZE)?.let { putParcelable(ContentResolver.EXTRA_SIZE, it) }
            putBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, true)
            if (extras.getBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB)) {
                putBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB, true)
            }
        }
        val mime = extras.getString(PickerImageClient.MIME) ?: "image/*"
        val afd = provider.openTypedAssetFile(Uri.parse(arg), mime, opts, CancellationSignal())
        thread {
            out.use {
                afd.use { source ->
                    val bitmap = source?.createInputStream()?.use { BitmapFactory.decodeStream(it) }
                    if (bitmap == null) {
                        receiver.send(PickerImageClient.REJECTED, Bundle())
                    } else {
                        val shared = bitmap.asShared()
                        try { receiver.send(PickerImageClient.OK, Bundle().apply { putParcelable(PickerImageClient.BITMAP, shared) }) }
                        finally { if (shared !== bitmap) shared.recycle(); bitmap.recycle() }
                    }
                }
            }
        }
        return Bundle()
    }
}
