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
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import com.android.photopicker.core.ApplicationOwned
import com.android.providers.media.vmdecode.frames.PickerImage
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The Photopicker's Glide configuration entrypoint.
 *
 * This class responds to the Glide libraries callbacks to configure Glide.
 */
@GlideModule
class PhotopickerGlideModule : AppGlideModule() {

    companion object {
        val TAG: String = "PhotopickerGlideModule"
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {

        val entryPoint =
            EntryPoints.get(context.getApplicationContext(), GlideSingletonEntryPoint::class.java)
        val resolver = entryPoint.contentResolver()

        Log.v(TAG, "Registering GlideLoadable into the Glide registry.")

        registry.append(
            GlideLoadable::class.java,
            PickerImage::class.java,
            PhotopickerModelLoaderFactory(resolver),
        )
        registry.append(
            GlideLoadable::class.java,
            Drawable::class.java,
            PhotopickerDrawableLoaderFactory(context),
        )
        // DiamaneOS: retain the checked bitmap, colour space and gain map through display.
        registry.prepend(PickerImage::class.java, android.graphics.Bitmap::class.java, PickerImage.BitmapDecoder())
        registry.prepend(PickerImage::class.java, Drawable::class.java, PickerImage.DrawableDecoder(context.resources))
    }

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // DiamaneOS: avoid encoded caches that omit part of the checked pixel resource.
        builder.setDefaultRequestOptions(RequestOptions().diskCacheStrategy(DiskCacheStrategy.NONE))
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            builder.setLogLevel(Log.VERBOSE)
        }
    }

    /** A custom EntryPoint for dependencies that need to be injected into the AppGlideModule */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GlideSingletonEntryPoint {

        @ApplicationOwned fun contentResolver(): ContentResolver
    }
}
