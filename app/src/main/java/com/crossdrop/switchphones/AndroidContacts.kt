package com.crossdrop.switchphones

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import com.crossdrop.core.contacts.VContact
import java.io.ByteArrayOutputStream

/** The phone's address book as [VContact]s, and back. Needs READ_CONTACTS / WRITE_CONTACTS. */
object AndroidContacts {

    fun count(context: Context): Int = runCatching {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID), null, null, null,
        )?.use { it.count } ?: 0
    }.getOrDefault(0)

    /** Every contact, merged across accounts the way the Contacts app shows them. */
    fun readAll(context: Context, withPhotos: Boolean): List<VContact> {
        val cr = context.contentResolver
        val byId = LinkedHashMap<Long, VContact>()
        val proj = arrayOf(
            ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE,
            "data1", "data2", "data3", "data4", "data5", "data6", "data7", "data8", "data9", "data10",
        )
        cr.query(ContactsContract.Data.CONTENT_URI, proj, null, null, ContactsContract.Data.CONTACT_ID)?.use { c ->
            fun col(i: Int) = c.getString(i).orEmpty()
            while (c.moveToNext()) {
                val id = c.getLong(0)
                var v = byId[id] ?: VContact()
                v = when (c.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> v.copy(
                        formattedName = col(2), given = col(3), family = col(4), prefix = col(5),
                        middle = col(6), suffix = col(7),
                    )
                    Phone.CONTENT_ITEM_TYPE -> v.copy(phones = v.phones + VContact.Labeled(col(2), phoneType(c.getInt(3))))
                    Email.CONTENT_ITEM_TYPE -> v.copy(emails = v.emails + VContact.Labeled(col(2), emailType(c.getInt(3))))
                    Organization.CONTENT_ITEM_TYPE -> v.copy(org = col(2), title = col(5), department = col(6))
                    StructuredPostal.CONTENT_ITEM_TYPE -> v.copy(
                        addresses = v.addresses + VContact.Address(
                            street = col(5), city = col(8), region = col(9), postcode = col(10), country = col(11),
                            type = if (c.getInt(3) == StructuredPostal.TYPE_WORK) "WORK" else if (c.getInt(3) == StructuredPostal.TYPE_HOME) "HOME" else "",
                        ),
                    )
                    Event.CONTENT_ITEM_TYPE -> if (c.getInt(3) == Event.TYPE_BIRTHDAY) v.copy(birthday = normDate(col(2))) else v
                    Website.CONTENT_ITEM_TYPE -> v.copy(urls = v.urls + col(2))
                    Note.CONTENT_ITEM_TYPE -> v.copy(note = col(2))
                    Nickname.CONTENT_ITEM_TYPE -> v.copy(nickname = col(2))
                    else -> v
                }
                byId[id] = v
            }
        }
        if (withPhotos) {
            for ((id, v) in byId) {
                val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
                val bytes = runCatching {
                    ContactsContract.Contacts.openContactPhotoInputStream(cr, uri, true)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes != null) byId[id] = v.copy(photo = bytes)
            }
        }
        return byId.values.map { v ->
            v.copy(
                phones = v.phones.filter { it.value.isNotBlank() }.distinctBy { VContact.normPhone(it.value) },
                emails = v.emails.filter { it.value.isNotBlank() }.distinctBy { it.value.lowercase() },
            )
        }.filter { it.displayName.isNotBlank() }
    }

    class ImportResult(val added: Int, val skipped: Int)

    /** Insert [contacts] that aren't already in the address book. */
    fun import(context: Context, contacts: List<VContact>, onProgress: (Int) -> Unit = {}): ImportResult {
        val existing = runCatching { readAll(context, withPhotos = false) }.getOrDefault(emptyList())
        val byName = existing.groupBy { VContact.normName(it.displayName) }
        var added = 0
        var skipped = 0
        contacts.forEachIndexed { i, c ->
            val dup = byName[VContact.normName(c.displayName)]?.any { it.sameAs(c) } == true
            if (dup) skipped++ else if (insert(context, c)) added++
            onProgress(i + 1)
        }
        return ImportResult(added, skipped)
    }

    private fun insert(context: Context, c: VContact): Boolean = runCatching {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        fun data(mime: String, vararg kv: Pair<String, Any?>) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, mime)
                .apply { kv.forEach { (k, v) -> withValue(k, v) } }
                .build()
        }
        data(
            StructuredName.CONTENT_ITEM_TYPE,
            StructuredName.DISPLAY_NAME to c.displayName,
            StructuredName.GIVEN_NAME to c.given.ifBlank { null },
            StructuredName.FAMILY_NAME to c.family.ifBlank { null },
            StructuredName.MIDDLE_NAME to c.middle.ifBlank { null },
            StructuredName.PREFIX to c.prefix.ifBlank { null },
            StructuredName.SUFFIX to c.suffix.ifBlank { null },
        )
        if (c.nickname.isNotBlank()) data(Nickname.CONTENT_ITEM_TYPE, Nickname.NAME to c.nickname)
        c.phones.forEach { data(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to it.value, Phone.TYPE to phoneTypeOf(it.type)) }
        c.emails.forEach { data(Email.CONTENT_ITEM_TYPE, Email.ADDRESS to it.value, Email.TYPE to emailTypeOf(it.type)) }
        if (c.org.isNotBlank() || c.title.isNotBlank() || c.department.isNotBlank()) data(
            Organization.CONTENT_ITEM_TYPE,
            Organization.COMPANY to c.org.ifBlank { null }, Organization.TITLE to c.title.ifBlank { null },
            Organization.DEPARTMENT to c.department.ifBlank { null }, Organization.TYPE to Organization.TYPE_WORK,
        )
        c.addresses.forEach { a ->
            data(
                StructuredPostal.CONTENT_ITEM_TYPE,
                StructuredPostal.STREET to a.street.ifBlank { null }, StructuredPostal.CITY to a.city.ifBlank { null },
                StructuredPostal.REGION to a.region.ifBlank { null }, StructuredPostal.POSTCODE to a.postcode.ifBlank { null },
                StructuredPostal.COUNTRY to a.country.ifBlank { null },
                StructuredPostal.TYPE to when (a.type) { "WORK" -> StructuredPostal.TYPE_WORK; "HOME" -> StructuredPostal.TYPE_HOME; else -> StructuredPostal.TYPE_OTHER },
            )
        }
        c.urls.forEach { data(Website.CONTENT_ITEM_TYPE, Website.URL to it, Website.TYPE to Website.TYPE_OTHER) }
        c.birthday?.let { data(Event.CONTENT_ITEM_TYPE, Event.START_DATE to it, Event.TYPE to Event.TYPE_BIRTHDAY) }
        if (c.note.isNotBlank()) data(Note.CONTENT_ITEM_TYPE, Note.NOTE to c.note)
        c.photo?.let { shrinkPhoto(it) }?.let { data(Photo.CONTENT_ITEM_TYPE, Photo.PHOTO to it) }
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    }.getOrDefault(false)

    /** Keeps a contact photo well under the binder transaction limit. */
    private fun shrinkPhoto(bytes: ByteArray): ByteArray? = runCatching {
        if (bytes.size < 200_000) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = 720f / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }.getOrNull()

    private fun phoneType(t: Int) = when (t) {
        Phone.TYPE_MOBILE -> "CELL"
        Phone.TYPE_HOME -> "HOME"
        Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> "WORK"
        Phone.TYPE_FAX_HOME, Phone.TYPE_FAX_WORK -> "FAX"
        Phone.TYPE_MAIN -> "MAIN"
        Phone.TYPE_PAGER -> "PAGER"
        else -> ""
    }

    private fun phoneTypeOf(s: String) = when (s) {
        "CELL" -> Phone.TYPE_MOBILE
        "HOME" -> Phone.TYPE_HOME
        "WORK" -> Phone.TYPE_WORK
        "FAX" -> Phone.TYPE_FAX_WORK
        "MAIN" -> Phone.TYPE_MAIN
        "PAGER" -> Phone.TYPE_PAGER
        else -> Phone.TYPE_MOBILE
    }

    private fun emailType(t: Int) = when (t) {
        Email.TYPE_HOME -> "HOME"
        Email.TYPE_WORK -> "WORK"
        else -> ""
    }

    private fun emailTypeOf(s: String) = when (s) {
        "HOME" -> Email.TYPE_HOME
        "WORK" -> Email.TYPE_WORK
        else -> Email.TYPE_OTHER
    }

    /** Android stores birthdays as "1990-05-17" or "--05-17" (no year); keep those, drop the rest. */
    private fun normDate(s: String): String? {
        val t = s.trim()
        return when {
            Regex("""^\d{4}-\d{2}-\d{2}$""").matches(t) -> t
            Regex("""^--\d{2}-\d{2}$""").matches(t) -> t
            Regex("""^\d{8}$""").matches(t) -> "${t.take(4)}-${t.substring(4, 6)}-${t.takeLast(2)}"
            else -> null
        }
    }
}
