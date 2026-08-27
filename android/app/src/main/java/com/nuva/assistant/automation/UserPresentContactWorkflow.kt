package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuva.assistant.command.ContactHandoffOperation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Exact contact is always chosen in Android's contact picker. */
object UserPresentContactWorkflow {
    sealed interface State {
        data object Idle : State
        data class Pending(val operation: ContactHandoffOperation, val id: Long) : State
        data class PickerActive(val operation: ContactHandoffOperation, val id: Long) : State
        data class Completed(val speech: String) : State
        data class Failed(val speech: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    @Synchronized
    fun request(operation: ContactHandoffOperation) {
        _state.value = State.Pending(operation, System.nanoTime())
    }

    @Synchronized
    fun markActive(id: Long): ContactHandoffOperation? {
        val pending = _state.value as? State.Pending ?: return null
        if (pending.id != id) return null
        _state.value = State.PickerActive(pending.operation, id)
        return pending.operation
    }

    fun handleSelected(context: Context, uri: Uri?): String {
        val active = _state.value as? State.PickerActive ?: return "Kono contact picker active nei."
        if (uri == null) {
            _state.value = State.Idle
            return "Contact selection batil korechi."
        }
        val intent = Intent(
            if (active.operation == ContactHandoffOperation.EDIT) Intent.ACTION_EDIT else Intent.ACTION_VIEW,
            uri,
        ).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or
                (if (active.operation == ContactHandoffOperation.EDIT) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0),
        )
        return try {
            context.startActivity(intent)
            val speech = if (active.operation == ContactHandoffOperation.EDIT) {
                "Selected contact edit screen khulechi — final Save apni chapun."
            } else {
                "Selected contact details khulechi."
            }
            _state.value = State.Completed(speech)
            speech
        } catch (_: Exception) {
            val speech = "Selected contact screen khulte parini."
            _state.value = State.Failed(speech)
            speech
        }
    }

    @Synchronized
    fun clear() {
        _state.value = State.Idle
    }
}
