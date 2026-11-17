import XCTest
@testable import CrossDropCore

final class ContactCardsTests: XCTestCase {
    private func card(_ name: String, photoBytes: Int) -> String {
        let photo = Data((0..<photoBytes).map { UInt8(truncatingIfNeeded: $0 &* 31) }).base64EncodedString()
        var lines = ["BEGIN:VCARD", "VERSION:3.0", "N:Doe;Jane;;;", "FN:\(name)", "TEL;TYPE=CELL:+1 555 0100"]
        // Folded the way our writer folds it: 75 octets, continuation lines start with a space.
        var p = "PHOTO;ENCODING=b;TYPE=JPEG:" + photo
        var folded = String(p.prefix(75)); p = String(p.dropFirst(75))
        while !p.isEmpty { folded += "\r\n " + p.prefix(74); p = String(p.dropFirst(74)) }
        lines.append(folded)
        lines.append("END:VCARD")
        return lines.joined(separator: "\r\n") + "\r\n"
    }

    func testPreviewDropsThePhotoAndKeepsTheRest() throws {
        let vcf = card("Jane Doe", photoBytes: 120_000)
        XCTAssertGreaterThan(vcf.utf8.count, maxInlineText)
        let preview = try XCTUnwrap(ContactCards.preview(vcf))
        XCTAssertFalse(preview.contains("PHOTO"))
        XCTAssertTrue(preview.contains("FN:Jane Doe"))
        XCTAssertTrue(preview.contains("TEL;TYPE=CELL:+1 555 0100"))
        XCTAssertTrue(preview.contains("END:VCARD"))
        XCTAssertLessThan(preview.utf8.count, 300)
    }

    func testStripsGroupedVCard21PhotosAndLogos() {
        let vcf = "BEGIN:VCARD\r\nVERSION:2.1\r\nFN:John Doe\r\nitem1.PHOTO;ENCODING=BASE64;JPEG:/9j/4AAQ\r\n AAAQABAAD\r\n HRofHh0\r\n\r\nTEL;CELL:555-0101\r\nLOGO;VALUE=uri:https://example.com/l.png\r\nEND:VCARD\r\n"
        let s = ContactCards.stripPhotos(vcf)
        XCTAssertFalse(s.contains("PHOTO"))
        XCTAssertFalse(s.contains("AAAQ"))
        XCTAssertFalse(s.contains("LOGO"))
        XCTAssertTrue(s.contains("TEL;CELL:555-0101"))
    }

    func testNoPreviewWhenTooLong() {
        let many = (1...2000).map { "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Person Number \($0)\r\nTEL:555 \($0)\r\nEND:VCARD\r\n" }.joined()
        XCTAssertNil(ContactCards.preview(many))
    }

    func testRecognisesVCards() {
        XCTAssertTrue(ContactCards.isVCard(name: "x.bin", mime: "text/x-vcard"))
        XCTAssertTrue(ContactCards.isVCard(name: nil, mime: "text/vcard; charset=utf-8"))
        XCTAssertTrue(ContactCards.isVCard(name: "Jane Doe.VCF", mime: nil))
        XCTAssertFalse(ContactCards.isVCard(name: "notes.txt", mime: "text/plain"))
        XCTAssertFalse(ContactCards.isVCard(name: nil, mime: nil))
    }

    func testNamesMatchTheKotlinSide() {
        XCTAssertEqual(ContactCards.fileName(names: ["Jane Doe"]), "Jane Doe.vcf")
        XCTAssertEqual(ContactCards.fileName(names: ["a", "b", "c"]), "3 contacts.vcf")
        XCTAssertEqual(ContactCards.fileName(names: ["a/b:c"]), "a_b_c.vcf")
        XCTAssertEqual(ContactCards.fileName(names: ["..."]), "Contact.vcf")
        XCTAssertEqual(ContactCards.title(names: ["Jane Doe", "x", "y"]), "Jane Doe and 2 others")
    }
}
