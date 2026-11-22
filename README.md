# ToDo-Listwerk

To-do-App mit Nextcloud-Synchronisation (CalDAV/VTODO) für macOS und iPad, mit Anbindung an Planwerk.
Angezeigter Programmname: **ToDo-Listwerk** (Repo, Bundle-ID, Datenordner und Schlüsselbund heißen weiter „Listwerk“).
Stand: Version 0.5.0, Android ist bewusst zurückgestellt.

## Fertige Mac-App laden

Unter [Releases](../../releases) liegt die App als DMG und ZIP (Universal, ad-hoc signiert, nicht notarisiert).
Hinweise zum ersten Start: `docs/RELEASE_NOTES.md`. Jeder Push auf `main` baut die App per GitHub Actions
(`.github/workflows/macos-build.yml`) neu; ein Tag `v*` legt ein Release an.

## Neu in 0.5.0 (macOS/iPad)

- **Englische Übersetzung, standardmäßig aktiv.** Einstellungen → Darstellung → „Sprache / Language“.
  Umsetzung ohne String Catalog: `L("deutscher Text")` schlägt in `Sources/ListwerkCore/Localization/L10nTable.swift`
  nach; fehlt ein Eintrag, erscheint Deutsch. `scripts/check_translations.py` meldet fehlende Übersetzungen
  (läuft auch im CI). Datumsformate folgen der gewählten Sprache, die Systemmenüs nach einem Neustart
  (`AppleLanguages`).
- **Schnelleingabe auf Englisch:** today, tomorrow, (on/next) Monday, in 3 days, at 8, 8am, 3:30pm, 8 pm, daily,
  weekdays, weekly, every Tuesday, every 2 weeks. Deutsch funktioniert unverändert; beide Sprachen gelten immer.
- Fehlermeldungen des Servers (`DAVError`) sind jetzt lesbar statt „error 2“.
- `scripts/install-mac.sh` installiert `/Applications/ToDo-Listwerk.app` und entfernt die alte `Listwerk.app`.

## Neu in 0.4.0 (macOS/iPad)

- **Schnelleingabe** in allen Eingabefeldern: „Elternbrief morgen 8 Uhr !“ → Titel, Termin, wichtig. Versteht heute,
  morgen, übermorgen, Wochentage, 24.11., in 3 Tagen, 8 Uhr, 8:30, um 14, täglich, werktags, jeden Dienstag,
  alle 2 Wochen (`QuickEntry`, Tests in `QuickEntryTests`).
- **Menüleiste** (Mac): Schnelleingabe mit Listenwahl, heute Fälliges zum Abhaken. **Globales Kürzel ⌃⌥N** öffnet
  überall ein kleines Eingabefenster.
- **Wiederholungen frei einstellen:** alle N Tage/Wochen/Monate/Jahre, Wochentage, Enddatum (`Recurrence.Rule`).
- **Anhänge hinzufügen:** mit Konto in die Nextcloud-Dateien (Ordner „Listwerk“, Link `index.php/f/<id>`),
  ohne Konto als Kopie im App-Ordner. Anhänge lassen sich öffnen und entfernen.
- **Planwerk in „Geplant“:** offene, terminierte Aufgaben aus dem Gantt-Plan erscheinen in „Geplant“ und lassen sich
  dort abhaken (`PUT /api/tasks/{id}`, braucht einen Token mit Schreibrecht). Ändern weiterhin in Planwerk.

## Neu in 0.3.0 (macOS/iPad)

- **Sichern und wiederherstellen** (Einstellungen → Sichern und Drucken): alle Listen als unverschlüsselte JSON-Datei,
  Einträge darin als iCalendar-Text (verlustfrei). Der Import ergänzt gleichnamige Listen und aktualisiert Einträge mit
  gleicher UID; gelöscht wird nichts, ein zweiter Import verdoppelt nichts (`Store.exportBackup`/`importBackup`).
- **PDF zum Ausdrucken und Abhaken:** Drucker-Knopf in der Werkzeugleiste (aktuelle Ansicht) oder in den Einstellungen
  (alle Listen, nur offene Einträge, je Liste eine A4-Seite). Android hat beides noch nicht.

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
- `Tests/ListwerkCoreTests`: Tests des Kerns (zuletzt 85 unter Linux grün; neu: englische Schnelleingabe, Übersetzung).
- `App/Shared`: SwiftUI-Oberfläche für macOS und iPad. `App/macOS`, `App/iPad`: plattformspezifische Dateien.
- `scripts/generate_themes.py`: erzeugt die Themes aus der offiziellen Catppuccin-Palette (MIT).
- `shared/`: Themes, Paletten und iCalendar-Fixtures zur späteren Wiederverwendung (Android).
- `docs/DECISIONS.md`: getroffene Entscheidungen und Abweichungen von der Spezifikation.

## Am Mac installieren

`scripts/install-mac.sh` baut die App ohne Sandbox und kopiert sie nach /Applications. Die Daten liegen dann in
`~/Library/Application Support/Listwerk`. Ein Build mit Sandbox sucht sie im Container und startet leer.

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
