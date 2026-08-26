package com.nuva.assistant.automation

import com.nuva.assistant.command.UserFileOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserPresentFileWorkflowTest {

    @Before
    fun reset() {
        UserPresentFileWorkflow.cancel()
    }

    @Test
    fun `request transitions through pending and picker active`() {
        val request = UserPresentFileWorkflow.request(UserFileOperation.PICK_PHOTO)
        val pending = UserPresentFileWorkflow.state.value as UserPresentFileWorkflow.State.Pending
        assertEquals(request, pending.request)

        assertNull(UserPresentFileWorkflow.markPickerActive(request.id + 1))
        val active = UserPresentFileWorkflow.markPickerActive(request.id)
        assertEquals(request, active)
        assertTrue(UserPresentFileWorkflow.state.value is UserPresentFileWorkflow.State.PickerActive)
    }

    @Test
    fun `new request replaces stale picker and cancel returns idle`() {
        val first = UserPresentFileWorkflow.request(UserFileOperation.OPEN_FILE)
        val second = UserPresentFileWorkflow.request(UserFileOperation.SHARE_VIDEO)
        assertTrue(first.id != second.id)
        assertEquals(second, UserPresentFileWorkflow.activeRequest())
        UserPresentFileWorkflow.cancel()
        assertTrue(UserPresentFileWorkflow.state.value is UserPresentFileWorkflow.State.Idle)
        assertNull(UserPresentFileWorkflow.activeRequest())
    }

    @Test
    fun `operation metadata keeps folder and sharing policy explicit`() {
        assertTrue(UserFileOperation.OPEN_FOLDER.usesFolderPicker)
        assertTrue(UserFileOperation.SHARE_FILE.sharesOutsideDevice)
        assertTrue(UserFileOperation.SHARE_PHOTO.sharesOutsideDevice)
        assertTrue(!UserFileOperation.READ_TEXT.sharesOutsideDevice)
    }
}
