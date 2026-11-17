package com.crossdrop.core

import com.crossdrop.core.contacts.ContactShare
import com.crossdrop.core.contacts.VCard
import com.crossdrop.core.contacts.VContact
import com.crossdrop.core.protocol.MAX_INLINE_TEXT
import org.junit.Assert.*
import org.junit.Test

class ContactShareTest {

    private val jane = VContact(
        given = "Jane", family = "Doe",
        phones = listOf(VContact.Labeled("+1 555 0100", "CELL")),
        emails = listOf(VContact.Labeled("jane@example.com")),
        photo = ByteArray(200_000) { (it * 31).toByte() },
    )

    @Test
    fun `preview drops the photo and keeps everything else`() {
        val vcf = VCard.write(jane)
        assertTrue(vcf.length > MAX_INLINE_TEXT)
        val preview = ContactShare.preview(vcf)!!
        assertFalse(preview.contains("PHOTO"))
        val back = VCard.parse(preview).single()
        assertEquals("Jane Doe", back.displayName)
        assertEquals(jane.phones, back.phones)
        assertEquals(jane.emails, back.emails)
        assertNull(back.photo)
    }

    @Test
    fun `strips vCard 2_1 photos with grouped names and folded base64`() {
        val vcf = """
            BEGIN:VCARD
            VERSION:2.1
            N:Doe;John;;;
            FN:John Doe
            item1.PHOTO;ENCODING=BASE64;JPEG:/9j/4AAQSkZJRgABAQ
             AAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8U
             HRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL

            TEL;CELL:555-0101
            LOGO;VALUE=uri:https://example.com/logo.png
            END:VCARD
        """.trimIndent().replace("\n", "\r\n")
        val stripped = ContactShare.stripPhotos(vcf)
        assertFalse(stripped.contains("PHOTO"))
        assertFalse(stripped.contains("AAAQ"))
        assertFalse(stripped.contains("LOGO"))
        val back = VCard.parse(stripped).single()
        assertEquals("John Doe", back.displayName)
        assertEquals("555-0101", back.phones.single().value)
    }

    @Test
    fun `no preview when even the photo-less cards are too long`() {
        val many = (1..2000).map { VContact(given = "Person", family = "Number $it", phones = listOf(VContact.Labeled("555 $it"))) }
        assertNull(ContactShare.preview(VCard.write(many)))
        assertNotNull(ContactShare.preview(VCard.write(many.take(20))))
    }

    @Test
    fun `recognises vCards by type or name`() {
        assertTrue(ContactShare.isVCard("x.bin", "text/x-vcard"))
        assertTrue(ContactShare.isVCard(null, "text/vcard; charset=utf-8"))
        assertTrue(ContactShare.isVCard("Jane Doe.VCF", null))
        assertTrue(ContactShare.isVCard("card.vcard", "application/octet-stream"))
        assertFalse(ContactShare.isVCard("notes.txt", "text/plain"))
        assertFalse(ContactShare.isVCard(null, null))
    }

    @Test
    fun `file names are safe and say how many`() {
        assertEquals("Jane Doe.vcf", ContactShare.fileName(listOf(jane)))
        assertEquals("3 contacts.vcf", ContactShare.fileName(listOf(jane, jane, jane)))
        assertEquals("a_b_c.vcf", ContactShare.fileName(listOf(VContact(formattedName = "a/b:c"))))
        assertEquals("Contact.vcf", ContactShare.fileName(listOf(VContact(formattedName = "..."))))
        assertEquals("Jane Doe and 2 others", ContactShare.title(listOf(jane, jane, jane)))
    }
}
