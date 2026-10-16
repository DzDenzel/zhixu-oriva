package com.planlock.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** CHANGE-054：知行附件的类别识别与大小显示。 */
class AssistantAttachmentsTest {
    @Test fun imagesByMime() = assertEquals(AttachmentKind.IMAGE, attachmentKindOf("image/jpeg", "IMG_0001.jpg"))
    @Test fun pdfByMimeOrExtension() {
        assertEquals(AttachmentKind.PDF, attachmentKindOf("application/pdf", "a"))
        assertEquals(AttachmentKind.PDF, attachmentKindOf("", "报告.PDF"))
    }
    @Test fun officeDocuments() {
        assertEquals(AttachmentKind.DOC, attachmentKindOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "x.docx"))
        assertEquals(AttachmentKind.SHEET, attachmentKindOf("application/octet-stream", "预算.xlsx"))
        assertEquals(AttachmentKind.SLIDES, attachmentKindOf("application/vnd.ms-powerpoint", "x.ppt"))
    }
    @Test fun textAndMarkdown() {
        assertEquals(AttachmentKind.TEXT, attachmentKindOf("text/markdown", "关于我.md"))
        assertEquals(AttachmentKind.TEXT, attachmentKindOf("", "notes.txt"))
    }
    @Test fun unknownFallsBackToFile() = assertEquals(AttachmentKind.OTHER, attachmentKindOf("application/zip", "a.zip"))
    @Test fun sizes() {
        assertEquals("", formatAttachmentSize(null))
        assertEquals("512 B", formatAttachmentSize(512))
        assertEquals("2 KB", formatAttachmentSize(2048))
        assertEquals("1.5 MB", formatAttachmentSize(1572864))
    }
}
