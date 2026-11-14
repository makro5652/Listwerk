# Entscheidungen

1. **SQLite direkt (Target `CSQLite`) statt GRDB.** Weniger Abhängigkeiten, kleinere App, testbar unter Linux.
   Abweichung von der Spezifikation; Kosten: eigener Wrapper (`SQLiteDatabase`).
2. **Parse-on-read.** Die Datenbank hält den originalen iCalendar-Text (`ics`) und die Basisversion (`base_ics`);
   Felder werden daraus gelesen. So gehen unbekannte Properties anderer Clients nicht verloren.
3. **Byte-Treue nur für unveränderte Dokumente.** Geänderte Dokumente werden neu gefaltet (75 Oktette, UTF-8-sicher).
4. **Erweiterungspräfix `X-LISTWERK-`** (Eintragsfarbe) statt `X-TODOAPP-`; zusätzlich RFC-7986 `COLOR`
   (nächster CSS-Name) für andere Clients.
5. **Merge:** feldweise Dreiwege-Zusammenführung; bei echtem Konflikt gewinnt das neuere `LAST-MODIFIED`,
   bei Gleichstand lokal. Konflikte stehen in `conflict_log`. Lokal geändert + Server gelöscht: neu anlegen.
   Lokal gelöscht + Server geändert: Server gewinnt.
6. **Planwerk:** nur `GET /api/state`, `GET /api/rev`, `POST /api/nextcloud/sync` mit Bearer-Token. Feldnamen als optionale Schnittstelle (frei konfigurierbar, Adresse und Token nur in den Einstellungen).
   In verknüpften Listen keine freien Schlagwörter; Datum nur als Tag.
7. **Themes:** Catppuccin-Werte aus der offiziellen `palette.json`. Latte nutzt für „Fläche“ die Farbe `crust`
   statt `surface0`, weil Nebentext sonst WCAG AA (4,5:1) verfehlt (4,05:1).
8. **Erinnerungen:** Fälligkeit ohne Uhrzeit → Bezugspunkt 09:00 Ortszeit; höchstens 64 geplante Mitteilungen (iOS-Limit).
9. **Android zurückgestellt.** `shared/` enthält Themes, Paletten und Fixtures für die spätere Portierung.
