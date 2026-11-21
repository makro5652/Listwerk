import SwiftUI
import ListwerkCore

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @State private var token = ""

    @State private var planwerkResult: String?
    @State private var msClientID = ""
    @State private var msResult: String?

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
            Section {
                Text("Listwerk \(ListwerkCoreInfo.version)").font(.footnote)
            }
        }
        .formStyle(.grouped)
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
