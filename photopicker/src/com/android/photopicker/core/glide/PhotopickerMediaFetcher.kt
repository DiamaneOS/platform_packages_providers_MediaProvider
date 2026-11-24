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

package com.android.photopicker.core.glide

import android.content.ContentResolver
import android.graphics.Point
import android.os.Bundle
import android.provider.CloudMediaProviderContract
import android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_ANIMATED_WEBP
import android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF
import com.android.photopicker.data.model.Media
import com.android.providers.media.vmdecode.frames.PickerImage
import com.android.providers.media.vmdecode.frames.PickerImageClient
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import java.io.IOException

/** Fetches typed checked pixels from the media item's profile, with cancellation and bounded retry. */
class PhotopickerMediaFetcher(
    private val resolver: ContentResolver,
    private val model: GlideLoadable,
    private val width: Int,
    private val height: Int,
    private val options: Options,
) : DataFetcher<PickerImage> {
    companion object {
        const val PICKER_IMAGE_CALL = PickerImageClient.METHOD
        const val EXTRA_PICKER_IMAGE_OUT = PickerImageClient.OUT
        const val EXTRA_PICKER_IMAGE_MIME = PickerImageClient.MIME
        const val EXTRA_PICKER_IMAGE_FRAMES = PickerImageClient.FRAMES
    }
    private val client = PickerImageClient()
    private var image: PickerImage? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in PickerImage>) {
        val extras = Bundle().apply {
            putParcelable(ContentResolver.EXTRA_SIZE, Point(width, height))
            putBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB,
                options.get(RESOLUTION_REQUESTED) == Resolution.THUMBNAIL)
            putString(PickerImageClient.MIME, model.getMimeTypeForGlide())
            putBoolean(PickerImageClient.FRAMES, animated())
        }
        try {
            image = client.load(resolver, model.getLoadableUri(), extras)
            callback.onDataReady(image)
        } catch (failure: IOException) {
            callback.onLoadFailed(failure)
        }
    }

    private fun animated(): Boolean {
        val media = model as? Media.Image ?: return false
        return options.get(RESOLUTION_REQUESTED) == Resolution.FULL &&
            (media.standardMimeTypeExtension == _SPECIAL_FORMAT_GIF ||
                media.standardMimeTypeExtension == _SPECIAL_FORMAT_ANIMATED_WEBP)
    }

    override fun cleanup() {
        client.close()
        try { image?.close() } catch (_: IOException) { }
    }
    override fun cancel() { client.close() }
    override fun getDataClass(): Class<PickerImage> = PickerImage::class.java
    override fun getDataSource(): DataSource = DataSource.LOCAL
}
