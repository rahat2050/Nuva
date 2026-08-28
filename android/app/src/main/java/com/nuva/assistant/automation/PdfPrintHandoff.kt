package com.nuva.assistant.automation

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream

/** Streams one user-selected PDF into Android's visible system print UI. */
object PdfPrintHandoff {
    sealed interface Result {
        data object Opened : Result
        data class Failed(val reason: String) : Result
    }

    fun print(context: Context, uri: Uri, displayName: String): Result {
        val mime = context.contentResolver.getType(uri)
        if (mime != null && mime != "application/pdf") return Result.Failed("Selected file PDF noy.")
        return try {
            val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            manager.print(
                "NUVA - ${displayName.take(80)}",
                UriPdfAdapter(context, uri, displayName),
                PrintAttributes.Builder().build(),
            )
            Result.Opened
        } catch (_: Exception) {
            Result.Failed("Android print preview khulte parini.")
        }
    }

    private class UriPdfAdapter(
        private val context: Context,
        private val uri: Uri,
        private val name: String,
    ) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(name.ifBlank { "document.pdf" })
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancellationSignal?.isCanceled == true) {
                                callback.onWriteCancelled()
                                return
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("PDF input unavailable")
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (error: Exception) {
                callback.onWriteFailed(error.message ?: "PDF write failed")
            }
        }
    }
}
