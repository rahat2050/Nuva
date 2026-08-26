package com.nuva.assistant.automation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.nuva.assistant.command.UserFileOperation
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * User-present Storage Access Framework / media workflow.
 *
 * NUVA never guesses a path and never receives broad storage permission. The
 * Android picker is the authority: only the URI the user explicitly selects is
 * handled, with temporary/persistable grants supplied by Android.
 */
object UserPresentFileWorkflow {

    sealed interface State {
        data object Idle : State
        data class Pending(val request: Request) : State
        data class PickerActive(val request: Request) : State
        data class Completed(val speech: String, val content: String? = null) : State
        data class Failed(val speech: String) : State
    }

    data class Request(
        val id: Long,
        val operation: UserFileOperation,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    @Synchronized
    fun request(operation: UserFileOperation): Request {
        val request = Request(System.nanoTime(), operation)
        _state.value = State.Pending(request)
        return request
    }

    @Synchronized
    fun markPickerActive(id: Long): Request? {
        val pending = _state.value as? State.Pending ?: return null
        if (pending.request.id != id) return null
        _state.value = State.PickerActive(pending.request)
        return pending.request
    }

    fun activeRequest(): Request? = when (val current = _state.value) {
        is State.Pending -> current.request
        is State.PickerActive -> current.request
        else -> null
    }

    @Synchronized
    fun cancel() {
        _state.value = State.Idle
    }

    @Synchronized
    fun clearResult() {
        if (_state.value is State.Completed || _state.value is State.Failed) _state.value = State.Idle
    }

    suspend fun handleSelected(context: Context, uri: Uri?): String {
        val request = activeRequest()
        if (request == null) return "Kono file request pending nei."
        if (uri == null) {
            cancel()
            return "File selection batil korechi."
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                persistReadGrant(context, uri, request.operation.usesFolderPicker)
                when (request.operation) {
                    UserFileOperation.OPEN_FILE -> {
                        openUri(context, uri)
                        complete("Selected file viewer e khulchi.")
                    }
                    UserFileOperation.SHARE_FILE -> {
                        shareUri(context, uri)
                        complete("Selected file share sheet e diyechi — recipient apni beche nin.")
                    }
                    UserFileOperation.READ_TEXT -> {
                        val text = readBoundedText(context, uri)
                        val name = displayName(context, uri)
                        complete("$name theke text porechi.", text)
                    }
                    UserFileOperation.OPEN_FOLDER -> {
                        val name = displayName(context, uri).ifBlank { "Selected folder" }
                        complete("$name folder access user grant korechen.")
                    }
                    UserFileOperation.PICK_PHOTO -> {
                        openUri(context, uri, "image/*")
                        complete("Selected photo khulchi.")
                    }
                    UserFileOperation.SHARE_PHOTO -> {
                        shareUri(context, uri, "image/*")
                        complete("Selected photo share sheet e diyechi — final recipient apni beche nin.")
                    }
                    UserFileOperation.PICK_VIDEO -> {
                        openUri(context, uri, "video/*")
                        complete("Selected video khulchi.")
                    }
                    UserFileOperation.SHARE_VIDEO -> {
                        shareUri(context, uri, "video/*")
                        complete("Selected video share sheet e diyechi — final recipient apni beche nin.")
                    }
                }
            }.getOrElse { error ->
                val speech = when (request.operation) {
                    UserFileOperation.READ_TEXT -> "Selected text file porte parini."
                    UserFileOperation.OPEN_FOLDER -> "Folder access nite parini."
                    else -> "Selected file/media handle korte parini."
                }
                fail("$speech ${error.message.orEmpty()}".trim())
            }
        }
    }

    @Synchronized
    private fun complete(speech: String, content: String? = null): String {
        _state.value = State.Completed(speech, content?.take(MAX_TEXT_CHARS))
        return speech
    }

    @Synchronized
    private fun fail(speech: String): String {
        _state.value = State.Failed(speech)
        return speech
    }

    private fun persistReadGrant(context: Context, uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    private fun openUri(context: Context, uri: Uri, forcedType: String? = null) {
        val mime = forcedType ?: context.contentResolver.getType(uri) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun shareUri(context: Context, uri: Uri, forcedType: String? = null) {
        val mime = forcedType ?: context.contentResolver.getType(uri) ?: "*/*"
        val share = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        share.clipData = ClipData.newUri(context.contentResolver, "NUVA selected file", uri)
        val chooser = Intent.createChooser(share, "Share with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun readBoundedText(context: Context, uri: Uri): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        context.contentResolver.openInputStream(uri)?.use { input ->
            while (output.size() < MAX_TEXT_CHARS) {
                val remaining = MAX_TEXT_CHARS - output.size()
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count <= 0) break
                output.write(buffer, 0, count)
            }
        } ?: error("input stream unavailable")
        return output.toString(Charsets.UTF_8.name()).ifBlank { "File ta khali." }
    }

    private fun displayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("").ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }
    }

    private const val MAX_TEXT_CHARS = 100_000
}
