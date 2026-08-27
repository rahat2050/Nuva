package com.nuva.assistant.automation

import com.nuva.assistant.command.NuvaAction
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
    fun `email attachment request carries bounded draft into picker state`() {
        val draft = NuvaAction.ComposeEmail("user@example.com", "subject", "body", attachmentRequested = true)
        val request = UserPresentFileWorkflow.requestEmailAttachment(draft)
        assertEquals(UserFileOperation.EMAIL_ATTACHMENT, request.operation)
        assertEquals("user@example.com", request.emailDraft?.recipient)
        assertTrue(request.emailDraft?.attachmentRequested == false)

        val multipleDraft = draft.copy(multipleAttachments = true)
        val multiple = UserPresentFileWorkflow.requestEmailAttachment(multipleDraft)
        assertEquals(UserFileOperation.EMAIL_ATTACHMENTS, multiple.operation)
        assertTrue(multiple.operation.usesMultiplePicker)
        assertTrue(multiple.emailDraft?.multipleAttachments == false)

        val mms = NuvaAction.ComposeMms("01712345678", "hello", attachmentRequested = true)
        val mmsRequest = UserPresentFileWorkflow.requestMmsAttachment(mms)
        assertEquals(UserFileOperation.MMS_ATTACHMENT, mmsRequest.operation)
        assertTrue(mmsRequest.mmsDraft?.attachmentRequested == false)
    }

    @Test
    fun `rename request keeps validated target name`() {
        val request = UserPresentFileWorkflow.request(UserFileOperation.RENAME_FILE, "report.pdf")
        assertEquals("report.pdf", request.newName)
        assertTrue(request.operation.needsBlockingConfirmation)
        assertTrue(request.operation.needsWriteGrant)
    }

    @Test
    fun `operation metadata keeps picker mutation and sharing policy explicit`() {
        assertTrue(UserFileOperation.OPEN_FOLDER.usesFolderPicker)
        assertTrue(UserFileOperation.SHARE_FILE.sharesOutsideDevice)
        assertTrue(UserFileOperation.SHARE_PHOTO.sharesOutsideDevice)
        assertTrue(UserFileOperation.EMAIL_ATTACHMENT.sharesOutsideDevice)
        assertTrue(UserFileOperation.SHARE_MULTIPLE_FILES.usesMultiplePicker)
        assertTrue(UserFileOperation.SHARE_MULTIPLE_PHOTOS.usesMultiplePicker)
        assertTrue(UserFileOperation.DELETE_FILE.changesSelectedContent)
        assertTrue(UserFileOperation.MOVE_FILE.changesSelectedContent)
        assertTrue(UserFileOperation.COPY_FILE.needsBlockingConfirmation)
        assertTrue(UserFileOperation.EDIT_PHOTO.needsWriteGrant)
        assertTrue(!UserFileOperation.READ_TEXT.sharesOutsideDevice)
    }
}
