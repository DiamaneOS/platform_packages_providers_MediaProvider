/* Copyright (C) 2026 DiamaneOS */
package com.android.photopicker.core.glide

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.android.providers.media.vmdecode.frames.PickerImage
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.signature.ObjectKey
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs against a real media fixture supplied by the device proof, without a per-item grant. */
class PickerImageDeviceProofTest {
    @Test
    fun signatureAuthorizedItemReachesTheCheckedGlideBitmap() {
        val supplied = InstrumentationRegistry.getArguments().getString("vmImage")
        assumeTrue("Device proof supplies a local picker media URI", supplied != null)
        val uri = Uri.parse(supplied!!)
        val context = InstrumentationRegistry.getInstrumentation().context
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(
            "com.android.providers.media.permission.MANAGE_CLOUD_MEDIA_PROVIDERS"))
        val provider = context.packageManager.resolveContentProvider("media", 0)!!
        assertNotEquals(provider.applicationInfo.uid, Process.myUid())
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkUriPermission(uri,
            Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        val model = object : GlideLoadable {
            override fun getSignature(resolution: Resolution) = ObjectKey(uri.toString())
            override fun getLoadableUri() = uri
            override fun getDataSource() = DataSource.LOCAL
        }
        val options = Options().set(RESOLUTION_REQUESTED, Resolution.THUMBNAIL)
        val fetcher = PhotopickerMediaFetcher(context.contentResolver, model, 256, 256, options)
        var result: PickerImage? = null
        var failure: Exception? = null
        try {
            fetcher.loadData(Priority.NORMAL, object : DataFetcher.DataCallback<PickerImage> {
                override fun onDataReady(data: PickerImage?) { result = data }
                override fun onLoadFailed(error: Exception) { failure = error }
            })
            if (failure != null) throw AssertionError("Real authorized picker load failed", failure)
            assertNotNull(result)
            val resource = requireNotNull(PickerImage.BitmapDecoder().decode(result!!, 256, 256, options))
            try {
                val bitmap: Bitmap = resource.get()
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                if (InstrumentationRegistry.getArguments().getString("requireHdr") == "true") {
                    assertTrue("Ultra HDR gain map reaches the final Glide bitmap", bitmap.hasGainmap())
                }
                Log.i("PickerDeviceProof", "checked bitmap ${bitmap.width}x${bitmap.height} caller=${Process.myUid()} sourceUser=${uri.userInfo ?: "context"}")
            } finally { resource.recycle() }
        } finally { fetcher.cleanup() }
    }
}
