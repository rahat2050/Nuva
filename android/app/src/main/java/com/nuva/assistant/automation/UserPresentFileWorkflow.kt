package com.nuva.assistant.automation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.UserFileOperation
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * User-present Storage Access Framework / media workflow.
 *
 * Android's picker is always the authority. Mutations use two gates: the
 * command-level confirmation, then a target-aware confirmation after the user
 * selects the exact source (and destination for copy/move).
 */
object UserPresentFileWorkflow {

    sealed interface State {
        data object Idle : State
        data class Pending(val request: Request) : State
        data class PickerActive(val request: Request) : State
        data class DestinationPending(val request: Request, val source: Uri, val sourceName: String) : State
        data class DestinationPickerActive(val request: Request, val source: Uri, val sourceName: String) : State
        data class AwaitingMutation(
            val request: Request,
            val source: Uri,
            val sourceName: String,
            val destinationTree: Uri? = null,
        ) : State
        data class MutationExecuting(val awaiting: AwaitingMutation) : State
        data class Completed(val speech: String, val content: String? = null) : State
        data class Failed(val speech: String) : State
    }

    data class Request(
        val id: Long,
        val operation: UserFileOperation,
        val newName: String? = null,
        val emailDraft: NuvaAction.ComposeEmail? = null,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    @Synchronized
    fun request(operation: UserFileOperation, newName: String? = null): Request {
        val request = Request(System.nanoTime(), operation, newName = newName)
        _state.value = State.Pending(request)
        return request
    }

    @Synchronized
    fun requestEmailAttachment(email: NuvaAction.ComposeEmail): Request {
        val request = Request(
            id = System.nanoTime(),
            operation = UserFileOperation.EMAIL_ATTACHMENT,
            emailDraft = email.copy(attachmentRequested = false),
        )
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

    @Synchronized
    fun markDestinationPickerActive(id: Long): State.DestinationPickerActive? {
        val pending = _state.value as? State.DestinationPending ?: return null
        if (pending.request.id != id) return null
        val active = State.DestinationPickerActive(pending.request, pending.source, pending.sourceName)
        _state.value = active
        return active
    }

    fun activeRequest(): Request? = when (val current = _state.value) {
        is State.Pending -> current.request
        is State.PickerActive -> current.request
        is State.DestinationPending -> current.request
        is State.DestinationPickerActive -> current.request
        is State.AwaitingMutation -> current.request
        is State.MutationExecuting -> current.awaiting.request
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
        val current = _state.value
        if (uri == null) {
            cancel()
            return "Selection batil korechi."
        }
        return withContext(Dispatchers.IO) {
            when (current) {
                is State.PickerActive -> handleSource(context, current.request, uri)
                is State.DestinationPickerActive -> handleDestination(context, current, uri)
                else -> "Kono picker request active nei."
            }
        }
    }

    private fun handleSource(context: Context, request: Request, uri: Uri): String = runCatching {
        persistGrant(context, uri, request.operation.needsWriteGrant)
        val name = displayName(context, uri).ifBlank { "selected item" }
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
                complete("$name theke text porechi.", text)
            }
            UserFileOperation.OPEN_FOLDER -> complete("$name folder access user grant korechen.")
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
            UserFileOperation.EMAIL_ATTACHMENT -> {
                val draft = request.emailDraft ?: error("email draft missing")
                when (val result = EmailComposer.composeWithAttachment(context, draft, uri)) {
                    EmailComposer.Result.Opened -> complete("Attachment-shoho email composer khulechi — final Send apni chapun.")
                    is EmailComposer.Result.Failed -> error(result.reason)
                }
            }
            UserFileOperation.RENAME_FILE,
            UserFileOperation.DELETE_FILE,
            -> awaitMutation(request, uri, name)
            UserFileOperation.COPY_FILE,
            UserFileOperation.MOVE_FILE,
            -> awaitDestination(request, uri, name)
            UserFileOperation.EDIT_PHOTO -> {
                editPhoto(context, uri)
                complete("Photo editor khulechi — edit kore final Save apni korben.")
            }
        }
    }.getOrElse { error -> fail("Selected file/media handle korte parini. ${error.message.orEmpty()}".trim()) }

    private fun handleDestination(
        context: Context,
        active: State.DestinationPickerActive,
        tree: Uri,
    ): String = runCatching {
        persistGrant(context, tree, write = true)
        val awaiting = State.AwaitingMutation(active.request, active.source, active.sourceName, tree)
        _state.value = awaiting
        "${active.sourceName} er destination select hoyeche — final ${active.request.operation.wireName} confirm korun."
    }.getOrElse { error -> fail("Destination folder use korte parini. ${error.message.orEmpty()}".trim()) }

    suspend fun confirmMutation(context: Context): String {
        val awaiting = beginMutation() ?: return "Kono file change pending nei."
        return withContext(Dispatchers.IO) {
            runCatching {
                when (awaiting.request.operation) {
                    UserFileOperation.RENAME_FILE -> rename(context, awaiting)
                    UserFileOperation.DELETE_FILE -> delete(context, awaiting.source, awaiting.sourceName)
                    UserFileOperation.COPY_FILE -> copyOrMove(context, awaiting, move = false)
                    UserFileOperation.MOVE_FILE -> copyOrMove(context, awaiting, move = true)
                    else -> error("operation is not a confirmed mutation")
                }
            }.getOrElse { error -> fail("File operation fail koreche. ${error.message.orEmpty()}".trim()) }
        }
    }

    @Synchronized
    private fun beginMutation(): State.AwaitingMutation? {
        val awaiting = _state.value as? State.AwaitingMutation ?: return null
        _state.value = State.MutationExecuting(awaiting)
        return awaiting
    }

    private fun rename(context: Context, awaiting: State.AwaitingMutation): String {
        val newName = awaiting.request.newName ?: error("new name missing")
        DocumentsContract.renameDocument(context.contentResolver, awaiting.source, newName)
            ?: error("provider rename support kore na")
        return complete("${awaiting.sourceName} rename kore $newName korechi.")
    }

    private fun delete(context: Context, uri: Uri, name: String): String {
        val deleted = if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
        if (!deleted) error("provider delete refuse koreche")
        return complete("$name delete korechi.")
    }

    private fun copyOrMove(context: Context, awaiting: State.AwaitingMutation, move: Boolean): String {
        val tree = awaiting.destinationTree ?: error("destination missing")
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val mime = resolver.getType(awaiting.source) ?: "application/octet-stream"
        val destination = DocumentsContract.createDocument(resolver, parent, mime, awaiting.sourceName)
            ?: error("destination file create hoyni")
        try {
            resolver.openInputStream(awaiting.source)?.use { input ->
                resolver.openOutputStream(destination, "w")?.use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
                    ?: error("destination output unavailable")
            } ?: error("source input unavailable")
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw error
        }

        if (!move) return complete("${awaiting.sourceName} selected folder-e copy korechi.")
        val sourceDeleted = runCatching {
            if (DocumentsContract.isDocumentUri(context, awaiting.source)) {
                DocumentsContract.deleteDocument(resolver, awaiting.source)
            } else {
                resolver.delete(awaiting.source, null, null) > 0
            }
        }.getOrDefault(false)
        return if (sourceDeleted) {
            complete("${awaiting.sourceName} selected folder-e move korechi.")
        } else {
            complete("File copy hoyeche, kintu source delete permission paini — tai source-o roye geche.")
        }
    }

    @Synchronized
    private fun awaitMutation(request: Request, source: Uri, name: String): String {
        _state.value = State.AwaitingMutation(request, source, name)
        return "$name select hoyeche — target dekhe final ${request.operation.wireName} confirm korun."
    }

    @Synchronized
    private fun awaitDestination(request: Request, source: Uri, name: String): String {
        _state.value = State.DestinationPending(request, source, name)
        return "$name source select hoyeche — ekhon destination folder select korun."
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

    private fun persistGrant(context: Context, uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    private fun openUri(context: Context, uri: Uri, forcedType: String? = null) {
        val mime = forcedType ?: context.contentResolver.getType(uri) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun shareUri(context: Context, uri: Uri, forcedType: String? = null) {
        val mime = forcedType ?: context.contentResolver.getType(uri) ?: "*/*"
        val share = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        share.clipData = ClipData.newUri(context.contentResolver, "NUVA selected file", uri)
        context.startActivity(Intent.createChooser(share, "Share with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun editPhoto(context: Context, uri: Uri) {
        val edit = Intent(Intent.ACTION_EDIT)
            .setDataAndType(uri, context.contentResolver.getType(uri) ?: "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        edit.clipData = ClipData.newUri(context.contentResolver, "NUVA selected photo", uri)
        context.startActivity(Intent.createChooser(edit, "Edit photo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("").ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }

    private const val MAX_TEXT_CHARS = 100_000
    private const val COPY_BUFFER_SIZE = 64 * 1024
}
