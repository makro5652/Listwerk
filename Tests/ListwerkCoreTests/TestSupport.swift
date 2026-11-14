import Foundation
import XCTest
@testable import ListwerkCore

enum Fixture {
    static func text(_ name: String) throws -> String {
        guard let url = Bundle.module.url(forResource: name, withExtension: "ics", subdirectory: "Fixtures") else {
            throw NSError(domain: "Fixture", code: 1, userInfo: [NSLocalizedDescriptionKey: "Fixture fehlt: \(name)"])
        }
        return try String(contentsOf: url, encoding: .utf8)
    }

    static let all = [
        "nextcloud-tasks", "apple-reminders", "thunderbird-vtimezone",
        "planwerk-linked", "long-unicode", "inline-attachment",
    ]
}

/// Fester Zeitpunkt für reproduzierbare Zeitstempel: 2026-11-11 12:00:00 UTC.
let testNow = Date(timeIntervalSince1970: 1_794_398_400)

func unfoldedLines(_ s: String) -> [String] { ICalText.unfold(s) }
