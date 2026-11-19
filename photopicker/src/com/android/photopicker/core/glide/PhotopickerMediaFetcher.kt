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
import android.os.ParcelFileDescriptor
import android.provider.CloudMediaProviderContract
import android.provider.MediaStore
import android.util.Log
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.data.DataFetcher.DataCallback
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * The worker unit of the [GlideLoadable] pipeline.
 *
 * Each load receives its own worker which is responsible for fetching data related to that specific
 * load operation.
 *
 * @property resolver The [ContentResolver] that will be used for fetching.
 * @property model The model this worker is responsible for loading.
 * @property width The requested width of the image.
 * @property height The requested height of the image.
 * @property options Any additional loading options.
 */
class PhotopickerMediaFetcher(
    private val resolver: ContentResolver,
    private val model: GlideLoadable,
    private val width: Int,
    private val height: Int,
    private val options: Options,
) : DataFetcher<InputStream> {

    companion object {
        val TAG: String = "PhotopickerMediaFetcher"

        // DiamaneOS: MediaProvider decodes the picker's images in the decode service's protected VM
        // and writes a PNG of the checked pixels into a pipe, so Glide here only ever decodes that PNG,
        // never an item's own bytes. The names are MediaProvider's (vmdecode/VmDecode.java).
        const val PICKER_IMAGE_CALL = "de.diamaneos.vmdecode.picker_image"
        const val EXTRA_PICKER_IMAGE_OUT = "de.diamaneos.vmdecode.picker_image_out"
        const val EXTRA_PICKER_IMAGE_MIME = "de.diamaneos.vmdecode.picker_image_mime"
    }

    // The read end of the pipe MediaProvider writes the image into, held open for Glide and closed
    // during [cleanup] or [cancel].
    @Volatile private var inputStream: InputStream? = null

    /**
     * The load request that is issued by Glide so this worker can begin to load it's data. The
     * callback should be used to notify Glide of the outcome.
     */
    override fun loadData(priority: Priority, callback: DataCallback<in InputStream>) {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        try {
            val extras =
                Bundle().apply {
                    putParcelable(ContentResolver.EXTRA_SIZE, Point(width, height))
                    if (options.get(RESOLUTION_REQUESTED)?.equals(Resolution.THUMBNAIL) ?: false) {
                        putBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB, true)
                    }
                    putString(EXTRA_PICKER_IMAGE_MIME, model.getMimeTypeForGlide())
                    putParcelable(EXTRA_PICKER_IMAGE_OUT, pipe[1])
                }
            // Returns at once: MediaProvider writes the image, or closes the pipe with an error.
            resolver.call(
                MediaStore.AUTHORITY,
                PICKER_IMAGE_CALL,
                model.getLoadableUri().toString(),
                extras,
            )
        } catch (ex: RuntimeException) {
            pipe[0].close()
            callback.onLoadFailed(
                FileNotFoundException("Failed to load data for ${model.getLoadableUri()}: $ex")
            )
            return
        } finally {
            pipe[1].close()
        }
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
        inputStream = stream
        callback.onDataReady(stream)
    }

    /**
     * Glide will call this when the resources this worker holds are no longer needed, so they can
     * be safely closed.
     */
    override fun cleanup() {
        try {
            inputStream?.close()
        } catch (ex: IOException) {
            Log.d(TAG, "Unexpected error during media fetcher cleanup", ex)
        }
    }

    /**
     * If this load hasn't been completed, and is no longer needed, Glide will request a
     * cancellation here. (Maybe the image is no longer in view, etc..)
     */
    override fun cancel() {
        // MediaProvider's write then fails and it stops.
        try {
            inputStream?.close()
        } catch (ex: IOException) {
            Log.d(TAG, "Unexpected error during media fetcher cancel", ex)
        }
    }

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    override fun getDataSource(): DataSource {
        return model.getDataSource()
    }
}
