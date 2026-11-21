import SwiftUI
import UniformTypeIdentifiers
import ListwerkCore

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @State private var token = ""

    @State private var planwerkResult: String?
    @State private var msClientID = ""
    @State private var msResult: String?
    @State private var exportFile: ExportFile?
    @State private var exportType = UTType.json
    @State private var exportName = ""
    @State private var importing = false
    @State private var backupResult: String?

    private func export(_ data: Data, _ type: UTType, _ name: String) {
        exportType = type; exportName = name; exportFile = ExportFile(data)
    }

    private func save() {
        model.planwerkToken = token
        planwerkResult = "Prüfe …"
        Task { planwerkResult = await model.refreshPlanwerk() }
    }

    var body: some View {
        @Bindable var model = model
        Form {
            Section("Darstellung") {
                Picker("Ansicht", selection: $model.layout) {
                    ForEach(AppLayout.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                Picker("Design", selection: $model.themeID) {
                    Text("System (Papier / Graphit)").tag(ThemeCatalog.systemID)
                    ForEach(ThemeCatalog.all) { Text($0.name).tag($0.id) }
                }
                ThemePreview(theme: model.theme(systemIsDark: scheme == .dark))
            }
            Section("Nextcloud") {
                if model.account == nil {
                    Text("Ohne Konto: Listen liegen nur auf diesem Mac.").foregroundStyle(.secondary)
                    Button("Mit Nextcloud anmelden und abgleichen") { model.showLogin = true }
                }
                if let a = model.account {
                    LabeledContent("Server", value: a.serverURL.host ?? a.serverURL.absoluteString)
                    LabeledContent("Konto", value: a.loginName)
                    Button("Jetzt abgleichen") { Task { await model.syncNow() } }
                    Button("Abmelden und lokale Daten löschen", role: .destructive) { model.logout() }
                }
            }
            Section("Planwerk (optional)") {
                TextField("Adresse, z. B. https://planwerk.beispiel.de", text: $model.planwerkURL)
                    .autocorrectionDisabled()
                SecureField("API-Token (pwk_…)", text: $token)
                    .onSubmit { save() }
                Button("Token speichern und prüfen") { save() }
                if let planwerkResult { Text(planwerkResult).font(.footnote).textSelection(.enabled) }
            }
            Section("Microsoft To Do (Import)") {
                TextField("App-ID (Client-ID aus Azure)", text: $msClientID)
                    .autocorrectionDisabled()
                    .onChange(of: msClientID) { model.msClientID = msClientID }
                if let c = model.msLogin {
                    Text("Code \(c.userCode) auf \(c.verificationURL.absoluteString) eingeben und bestätigen.")
                        .textSelection(.enabled)
                    Link("Anmeldeseite öffnen", destination: c.verificationURL)
                }
                Button(model.msConnected ? "Jetzt importieren" : "Verbinden und importieren") {
                    msResult = "Läuft …"
                    Task { msResult = await model.importMSTodo() }
                }
                if model.msConnected { Button("Verbindung trennen", role: .destructive) { model.disconnectMSTodo() } }
                if let msResult { Text(msResult).font(.footnote).textSelection(.enabled) }
                Text("Einseitig: To Do → Listwerk. Änderungen in To Do überschreiben beim nächsten Import die Listwerk-Einträge.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Sichern und Drucken") {
                Button("Alle Listen sichern (JSON) …") {
                    do { export(try model.store.exportBackup(), .json, "Listwerk-Sicherung " + Date().formatted(.iso8601.year().month().day())) }
                    catch { backupResult = "Fehler: \(error.localizedDescription)" }
                }
                Button("Sicherung importieren …") { importing = true }
                Button("Alle Listen als PDF zum Ausdrucken …") { export(model.pdfAllLists(), .pdf, "Listwerk") }
                if let backupResult { Text(backupResult).font(.footnote).textSelection(.enabled) }
                Text("Die Sicherung ist unverschlüsselt. Beim Import werden gleichnamige Listen ergänzt und Einträge mit gleicher ID aktualisiert; gelöscht wird nichts.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section {
                Text("Listwerk \(ListwerkCoreInfo.version)").font(.footnote)
            }
        }
        .formStyle(.grouped)
        .fileExporter(isPresented: Binding { exportFile != nil } set: { if !$0 { exportFile = nil } },
                      document: exportFile, contentType: exportType, defaultFilename: exportName) { result in
            switch result {
            case .success(let url): backupResult = "Gespeichert: \(url.lastPathComponent)"
            case .failure(let e): backupResult = "Fehler: \(e.localizedDescription)"
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url): backupResult = model.importBackup(from: url)
            case .failure(let e): backupResult = "Fehler: \(e.localizedDescription)"
            }
        }
        .frame(minWidth: 420, minHeight: 480)
        .onAppear { token = model.planwerkToken; msClientID = model.msClientID }
    }
}

struct ThemePreview: View {
    let theme: Theme
    var body: some View {
        HStack(spacing: 0) {
            ForEach([\ThemeColors.background, \.sidebar, \.surface, \.accent, \.success, \.warning, \.danger], id: \.self) { kp in
                Rectangle().fill(theme.color(kp))
            }
        }
        .frame(height: 24)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}
