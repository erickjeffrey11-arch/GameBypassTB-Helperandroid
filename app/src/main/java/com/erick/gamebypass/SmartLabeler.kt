package com.erick.gamebypass

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.imagedescription.ImageDescriber
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import java.util.concurrent.Executor

/**
 * Smart labels: asks the AI built into the phone (Gemini Nano, offline and free)
 * to describe a small picture, like the area around an unlabeled button.
 */
class SmartLabeler(context: Context, private val executor: Executor) {

    private val describer: ImageDescriber =
        ImageDescription.getClient(ImageDescriberOptions.builder(context).build())

    /** null = not checked yet, true = ready, false = can't be used (see [problem]). */
    var ready: Boolean? = null
        private set
    var problem: String? = null
        private set
    private var preparing = false

    /** Makes sure the on-phone AI is there, downloading it the first time if needed. */
    fun prepare(done: (Boolean) -> Unit) {
        if (ready != null) { done(ready == true); return }
        if (preparing) return
        preparing = true
        val future = describer.checkFeatureStatus()
        future.addListener({
            val status = try {
                future.get()
            } catch (e: Exception) {
                finish(false, e.cause?.message ?: e.message, done); null
            }
            when (status) {
                null -> {}
                FeatureStatus.AVAILABLE -> finish(true, null, done)
                FeatureStatus.UNAVAILABLE -> finish(false, "the phone's offline AI isn't available", done)
                else -> describer.downloadFeature(object : DownloadCallback {
                    override fun onDownloadStarted(bytesToDownload: Long) {}
                    override fun onDownloadProgress(totalBytesDownloaded: Long) {}
                    override fun onDownloadCompleted() { finish(true, null, done) }
                    override fun onDownloadFailed(e: GenAiException) { finish(false, e.message, done) }
                })
            }
        }, executor)
    }

    private fun finish(ok: Boolean, why: String?, done: (Boolean) -> Unit) {
        preparing = false
        ready = ok
        problem = why
        done(ok)
    }

    /** Describes a picture. Calls back with the description, or null and a reason. */
    fun describe(picture: Bitmap, callback: (String?, String?) -> Unit) {
        try {
            val future = describer.runInference(ImageDescriptionRequest.builder(picture).build())
            future.addListener({
                try {
                    callback(future.get().description, null)
                } catch (e: Exception) {
                    callback(null, e.cause?.message ?: e.message)
                }
            }, executor)
        } catch (e: Exception) {
            callback(null, e.message)
        }
    }

    fun close() = runCatching { describer.close() }
}
