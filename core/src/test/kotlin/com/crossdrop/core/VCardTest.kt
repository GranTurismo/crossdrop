package com.crossdrop.core

import com.crossdrop.core.contacts.VCard
import com.crossdrop.core.contacts.VContact
import org.junit.Assert.*
import org.junit.Test

class VCardTest {

    @Test
    fun `round trip keeps every field`() {
        val c = VContact(
            family = "Kupat; Adze", given = "Gio", middle = "M", prefix = "Dr.", nickname = "G",
            org = "Cross, Drop", department = "R&D", title = "Lead",
            phones = listOf(VContact.Labeled("+995 555 12 34 56", "CELL"), VContact.Labeled("032 222", "WORK")),
            emails = listOf(VContact.Labeled("a@b.ge", "HOME")),
            addresses = listOf(VContact.Address("Rustaveli 1\nApt 2", "Tbilisi", "", "0108", "Georgia", "HOME")),
            urls = listOf("https://example.com"),
            birthday = "1990-05-17",
            note = "line one\nline two; with, punctuation",
            photo = ByteArray(300) { it.toByte() },
        )
        val back = VCard.parse(VCard.write(c)).single()
        assertEquals(c.family, back.family)
        assertEquals(c.given, back.given)
        assertEquals(c.middle, back.middle)
        assertEquals(c.prefix, back.prefix)
        assertEquals(c.nickname, back.nickname)
        assertEquals(c.org, back.org)
        assertEquals(c.department, back.department)
        assertEquals(c.title, back.title)
        assertEquals(c.phones, back.phones)
        assertEquals(c.emails, back.emails)
        assertEquals(c.addresses, back.addresses)
        assertEquals(c.urls, back.urls)
        assertEquals(c.birthday, back.birthday)
        assertEquals(c.note, back.note)
        assertArrayEquals(c.photo, back.photo)
    }

    @Test
    fun `long non-ascii lines fold and unfold`() {
        val c = VContact(given = "ნიკოლოზ".repeat(20), family = "ბარათაშვილი")
        val text = VCard.write(c)
        assertTrue(text.split("\r\n").all { it.toByteArray().size <= 75 })
        assertEquals(c.given, VCard.parse(text).single().given)
    }

    @Test
    fun `reads Apple vCard 3 with item groups`() {
        val apple = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Apple Inc.//iPhone OS 18.0//EN
            N:Appleseed;Johnny;;;
            FN:Johnny Appleseed
            ORG:Apple Inc.;
            item1.TEL;type=pref:+1 (408) 555-0100
            item1.X-ABLabel:_${'$'}!<Mobile>!${'$'}_
            TEL;type=CELL;type=VOICE:(408) 555-0199
            EMAIL;type=INTERNET;type=HOME;type=pref:johnny@example.com
            item2.ADR;type=HOME;type=pref:;;1 Infinite Loop;Cupertino;CA;95014;United States
            BDAY;value=date:1976-04-01
            PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRgABAQ
              AAAQABAAD/
            END:VCARD
        """.trimIndent()
        val c = VCard.parse(apple).single()
        assertEquals("Johnny Appleseed", c.displayName)
        assertEquals(listOf("+1 (408) 555-0100", "(408) 555-0199"), c.phones.map { it.value })
        assertEquals("CELL", c.phones[1].type)
        assertEquals("johnny@example.com", c.emails.single().value)
        assertEquals("Cupertino", c.addresses.single().city)
        assertEquals("1976-04-01", c.birthday)
        assertNotNull(c.photo)
    }

    @Test
    fun `reads Android vCard 2_1 quoted printable`() {
        val android = "BEGIN:VCARD\r\nVERSION:2.1\r\n" +
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E1=83=91=E1=83=90=E1=83=A0=E1=83=90;=E1=83=9C=E1=83=98=\r\n" +
            "=E1=83=99=E1=83=90;;;\r\n" +
            "TEL;CELL:555-1234\r\nTEL;HOME;VOICE:555-9999\r\nEND:VCARD\r\n"
        val c = VCard.parse(android).single()
        assertEquals("ბარა", c.family)
        assertEquals("ნიკა", c.given)
        assertEquals(listOf("CELL", "HOME"), c.phones.map { it.type })
    }

    @Test
    fun `duplicates match by name plus phone or email`() {
        val a = VContact(given = "Ana", family = "B", phones = listOf(VContact.Labeled("+995 599 11 22 33")))
        val b = VContact(formattedName = "ana b", phones = listOf(VContact.Labeled("599112233")))
        val c = VContact(given = "Ana", family = "B", phones = listOf(VContact.Labeled("111")))
        assertTrue(a.sameAs(b))
        assertFalse(a.sameAs(c))
    }
}
