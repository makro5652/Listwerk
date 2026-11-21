# Listwerk (Arbeitstitel)

To-do-App mit Nextcloud-Synchronisation (CalDAV/VTODO) für macOS und iPad, mit Anbindung an Planwerk.
Stand: Version 0.1.0, Android ist bewusst zurückgestellt.

## Neu in 0.2.0 (macOS/iPad, gleiche Funktionen wie Android 0.2.0)

- **Ohne Anmeldung nutzbar:** Beim ersten Start „Ohne Anmeldung nutzen“. Listen liegen dann unter `local:/…` nur auf dem
  Gerät. Bei einer späteren Anmeldung (Einstellungen → „Mit Nextcloud anmelden“) übernimmt `Store.adoptLocalLists` sie
  ins Konto; der nächste Abgleich legt die Listen an und lädt alle Einträge hoch (Tests in `LocalModeRecurrenceTests`).
- **Ansicht „Wie Microsoft To Do“** (Einstellungen → Darstellung): Seitenleiste mit farbigen Symbolen, Listenansicht auf
  Listenfarbe mit großem Titel, Karten mit Kreis und Stern, „Aufgabe hinzufügen“, einklappbarer Abschnitt „Erledigt“.
- **In beiden Ansichten:** Mein Tag, Wichtig, Geplant, Alle, Erledigt; Suche; Sortieren; Stern; Wiederholen (täglich,
  werktags, wöchentlich, monatlich, jährlich; Erledigen legt den nächsten Termin an). „Mein Tag“ gilt nur auf dem Gerät.

## Aufbau

- `Sources/ListwerkCore`: plattformunabhängiger Kern (iCalendar-Codec, SQLite-Speicher, CalDAV-Client,
  Login Flow v2, Sync-Engine mit Dreiwege-Merge, Themes, Planwerk-Client, Erinnerungsplanung).
- `Tests/ListwerkCoreTests`: 85 Tests, unter Linux (Swift 6.1.2) grün.
- `App/Shared`: SwiftUI-Oberfläche für macOS und iPad. `App/macOS`, `App/iPad`: plattformspezifische Dateien.
- `scripts/generate_themes.py`: erzeugt die Themes aus der offiziellen Catppuccin-Palette (MIT).
- `shared/`: Themes, Paletten und iCalendar-Fixtures zur späteren Wiederverwendung (Android).
- `docs/DECISIONS.md`: getroffene Entscheidungen und Abweichungen von der Spezifikation.

## Am Mac starten

1. Xcode 15.3 oder neuer, `brew install xcodegen`.
2. `swift test` (prüft den Kern; sollte ohne Änderung laufen).
3. In `project.yml` die Bundle-IDs (`app.listwerk.*`) durch eigene ersetzen, dann `xcodegen`
   und `open Listwerk.xcodeproj`. Team/Signing in Xcode wählen.
4. Schema `Listwerk-macOS` oder `Listwerk-iPad` starten, Nextcloud-Adresse eingeben; die Anmeldung läuft
   über Login Flow v2 im Browser, die App speichert nur ein App-Passwort im Schlüsselbund.

## Was geprüft ist und was nicht (bitte lesen)

Geprüft (automatisierte Tests, Linux): iCalendar-Round-Trip, Merge, Sync gegen einen In-Memory-Fake-Server,
Speicher, CalDAV-Aufrufe gegen Mock-Transport, Themes (WCAG-AA-Kontrast), Planwerk-Client, Erinnerungsplanung.
Messung: 5.000 Einträge, Datenbank rund 3 MB, alle Einträge laden unter 1 s (Linux, kein Gerätewert).

**Nicht geprüft:**
- Die gesamte SwiftUI-Schicht (`App/`) wurde nie kompiliert, da hier kein macOS/Xcode verfügbar war.
  Mit Compilerfehlern und Anpassungen ist zu rechnen, besonders bei Drag-and-drop (`.draggable` zusammen mit
  `.onMove`) und `@Observable`/`@Bindable`.
- Schlüsselbund und lokale Mitteilungen (Apple-Frameworks) sind ungetestet.
- Die CalDAV-XML-Antworten in den Tests sind nach dem bekannten sabre/dav-Format nachgebaut. Ein Test gegen
  eine echte Nextcloud-Instanz (z. B. Docker `nextcloud`) steht aus und ist der wichtigste nächste Schritt.
- Die iCalendar-Fixtures sind synthetisch nachgebaut, nicht aus echten Apple-/Thunderbird-Exporten.
- Byte-Treue gilt nur für unveränderte Dokumente; geänderte Einträge werden neu gefaltet.

## Noch nicht umgesetzt

Teilen von Listen (Milestone 6; geteilte Listen werden gelesen und als schreibgeschützt gekennzeichnet, falls der
Server das so meldet), Android, Widgets, Wiederholungsregeln in der Oberfläche (werden verlustfrei durchgereicht),
Datei-Anhänge hinzufügen, Konfliktansicht (Konflikte werden protokolliert).
