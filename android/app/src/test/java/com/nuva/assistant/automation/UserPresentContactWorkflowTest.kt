package com.nuva.assistant.automation

import com.nuva.assistant.command.ContactHandoffOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserPresentContactWorkflowTest {
    @Before
    fun reset() = UserPresentContactWorkflow.clear()

    @Test
    fun `contact workflow requires matching picker request id`() {
        UserPresentContactWorkflow.request(ContactHandoffOperation.EDIT)
        val pending = UserPresentContactWorkflow.state.value as UserPresentContactWorkflow.State.Pending
        assertNull(UserPresentContactWorkflow.markActive(pending.id + 1))
        assertEquals(ContactHandoffOperation.EDIT, UserPresentContactWorkflow.markActive(pending.id))
        assertTrue(UserPresentContactWorkflow.state.value is UserPresentContactWorkflow.State.PickerActive)
    }

    @Test
    fun `new request replaces old state and clear is idle`() {
        UserPresentContactWorkflow.request(ContactHandoffOperation.VIEW)
        UserPresentContactWorkflow.request(ContactHandoffOperation.EDIT)
        val pending = UserPresentContactWorkflow.state.value as UserPresentContactWorkflow.State.Pending
        assertEquals(ContactHandoffOperation.EDIT, pending.operation)
        UserPresentContactWorkflow.clear()
        assertTrue(UserPresentContactWorkflow.state.value is UserPresentContactWorkflow.State.Idle)
    }
}
