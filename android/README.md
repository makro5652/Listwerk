# Listwerk für Android

Android-Portierung der Swift-App Listwerk (Aufgaben auf Nextcloud-CalDAV, optional Planwerk).
Kotlin, Jetpack Compose (Material 3), Room, OkHttp, WorkManager, Glance. `minSdk` 26, `compileSdk`/`targetSdk` 36, Paket `app.listwerk.android`.

## Neu in 0.5.0

- **Englische Oberfläche, standardmäßig aktiv.** Einstellungen → Design → „Sprache / Language“ (English, Deutsch).
  Englisch liegt in `res/values`, Deutsch in `res/values-de`. Ab Android 13 nutzt die App die App-Sprache des Systems
  (auch unter Systemeinstellungen → Apps → ToDo-Listwerk → Sprache), davor `AppLocale.wrap` in `attachBaseContext`.
  Texte aus dem Kern (Fehlermeldungen, Erinnerungstitel) folgen über `CoreText`.
- **Schnelleingabe wie am Mac** (`core/…/QuickEntry.kt`, Tests in `QuickEntryTest`): „Elternbrief morgen 8 Uhr !“,
  „Call parents tomorrow 8am !“, next Monday, at 3:30pm, every 2 weeks, werktags, in 3 Tagen. Gilt im Eingabefeld
  und im Schnelleintrag (Kachel, Shortcut, Teilen); gewählte Datums-Chips haben Vorrang.
- Angezeigter Name **ToDo-Listwerk** (Paket `app.listwerk.android` unverändert).
- Hinweis: Die Debug-APK ist mit einem anderen Debug-Schlüssel signiert als 0.3.0. Ein Update über die alte App
  scheitert; vorher deinstallieren (lokale Listen ohne Konto gehen dabei verloren).

## Aufbau

- `core/` – reines Kotlin/JVM (`app.listwerk.core`): verlustfreies iCalendar-Modell, feldweiser Dreiwege-Merge, `Store`, CalDAV-Client, Login Flow v2, `SyncEngine`, Themes/Paletten, Planwerk-Client, Erinnerungsplanung. Enthält die portierten Tests samt `FakeServer`.
- `app/` – Android-Anwendung: Room-Backend (`RoomBackend`), Keystore-Geheimnisspeicher, OkHttp-Transport, WorkManager-Abgleich, AlarmManager-Erinnerungen, Compose-Oberfläche, Glance-Widget, `QuickAddActivity`, Shortcut, Schnelleinstellungs-Kachel.
- Themes und Farbpaletten werden beim Bauen aus `../shared` in die APK kopiert, die Testfixtures aus `../shared/fixtures` gelesen.

## Ohne Konto, Darstellung „Wie Microsoft To Do“

- Beim ersten Start: „Ohne Anmeldung nutzen“. Listen liegen dann unter `local:/…` nur auf dem Gerät. Meldet man sich später
  an (Einstellungen → „Mit Nextcloud anmelden“), übernimmt `Store.adoptLocalLists` sie ins Konto; der nächste Abgleich
  legt die Listen per `MKCALENDAR` an und lädt alle Einträge hoch. Getestet in `SyncEngineTest` und `StoreContractTest`.
- Einstellungen → Darstellung: „Listwerk“ oder „Wie Microsoft To Do“ (Startseite mit Listen, farbige Listenansicht,
  Karten mit Stern, Abschnitt „Erledigt“, „+ Aufgabe hinzufügen“).
- In beiden Darstellungen: intelligente Listen „Mein Tag“, „Wichtig“, „Geplant“, „Aufgaben“ (alle), „Erledigt“;
  Suche; Sortieren (eigene Reihenfolge, Wichtigkeit, Fälligkeit, alphabetisch, Erstellung); Stern = hohe Priorität;
  Wiederholen (täglich, werktags, wöchentlich, monatlich, jährlich): Erledigen legt den nächsten Termin als neuen Eintrag an.
- „Mein Tag“ wird nur auf dem Gerät gemerkt und beginnt jeden Tag leer (Fälliges und Überfälliges erscheint automatisch).

## Bauen und prüfen

```
./gradlew :core:test
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:assembleRelease      # R8 aktiv, unsigniert
```

Voraussetzungen: JDK 17+, Android SDK mit Plattform 36 (`local.properties` mit `sdk.dir`, nicht eingecheckt).

## Wichtig: Stand der Prüfung

Diese Portierung wurde ohne Emulator und ohne Gerät entwickelt. Automatisch geprüft sind nur JVM-Tests (Kern, Abgleich gegen einen
simulierten Server, Room-Speicher unter Robolectric), Kompilierung und Lint. **Nicht auf einem Gerät geprüft** sind:

- die gesamte Compose-Oberfläche (Layout, Ziehen-und-Ablegen, Wischen zum Löschen, Detailblatt, Dialoge, Themes)
- Widget (Glance), Konfigurationsdialog des Widgets, Erledigen im Widget
- Schnelleinstellungs-Kachel, statisches Shortcut, Teilen-Intent, `QuickAddActivity`
- Benachrichtigungen, Berechtigungsabfragen (POST_NOTIFICATIONS, exakte Alarme), Neuplanung nach Neustart
- der Keystore-gestützte Geheimnisspeicher (läuft nur auf echtem Android und ist von keinem Test abgedeckt)
- die Anmeldung gegen eine echte Nextcloud (Login Flow v2 im Browser) und der Abgleich mit echten Servern
- die Verbindung zu einem echten Planwerk
- das Verhalten der R8-verkleinerten Release-APK (nur gebaut, nicht gestartet)

## Checkliste für Android Studio (Emulator oder Gerät)

1. Projekt `android/` öffnen, Gradle-Sync, App starten. Kaltstart ohne Konto zeigt sofort die Anmeldung (kein Splash).
2. Anmeldung: Nextcloud-Adresse eingeben, im Browser bestätigen, zurück zur App; Listen erscheinen. Danach Flugmodus: Einträge lassen sich weiter anlegen und erledigen.
3. Listen anlegen, umbenennen, Farbe ändern, löschen; Schreibschutz und „geteilt“-Hinweis bei geteilten Listen prüfen.
4. Eintrag anlegen (Eingabefeld unten), mit Farbe sowie Heute/Morgen/Datum; erledigen, wischen zum Löschen, Rückgängig.
5. Ziehen-und-Ablegen innerhalb einer Liste; Eintrag in eine andere Liste verschieben (Detailblatt).
6. Detailblatt: Titel, Notiz, Fälligkeit (ganztägig/mit Uhrzeit), Priorität, Schlagwörter, Link, Farbe, Unteraufgaben, Anhänge werden angezeigt. Wiederholung bleibt unverändert.
7. Erinnerung setzen: Berechtigung für Benachrichtigungen gewähren, Mitteilung abwarten, „Erledigt“-Aktion, Gerät neu starten und prüfen, dass die Erinnerung weiter kommt. In den Einstellungen exakte Alarme erlauben.
8. Einstellungen: alle Themes (Papier, Graphit, vier Catppuccin, System), „Eingabefeld beim Start fokussieren“, Planwerk-Adresse und Token (`pwk_…`) speichern/testen; verknüpfte Listen zeigen Art/Gruppe/Zugewiesen statt Schlagwörter.
9. Widget platzieren (Liste oder „Alle offenen“ wählen): Farben und Fälligkeit, Erledigen im Widget, „+“ öffnet den Schnelleintrag, Aktualisierung nach Änderungen in der App und nach Abgleich.
10. Schnelleintrag über Shortcut (Langdruck auf das Symbol), Kachel (Schnelleinstellungen bearbeiten) und „Teilen“ von Text aus einer anderen App: speichert lokal und schließt sich.
11. Release-APK (`assembleRelease`, mit eigenem Schlüssel signieren) starten und die Punkte 2–6 wiederholen, um R8-Probleme auszuschließen.
12. Abmelden: lokale Daten, Geheimnisse und Widget-Inhalte verschwinden.

## Abweichungen von der Swift-Referenz

- Zeit: `java.time` (`Instant`, `ZoneId`) statt `Date`/`TimeZone`.
- XML: SAX mit Namespaces statt `XMLParser`.
- Zugangsdaten: App-Passwort und Planwerk-Token liegen verschlüsselt (AES-256-GCM, Schlüssel im Android Keystore) in Shared Preferences, nie in der Room-Datenbank.
- Abgleich: WorkManager (periodisch, plus kurzzeitverzögert nach lokalen Änderungen) statt Timer; die Oberfläche liest ausschließlich lokal.
- Erinnerungen: es wird immer nur der nächste Alarm im AlarmManager vorgemerkt und danach neu geplant.
- Nicht umgesetzt (wie vorgegeben): Freigaben verwalten, Anhänge hochladen, Wiederholungen bearbeiten (werden verlustfrei durchgereicht).
