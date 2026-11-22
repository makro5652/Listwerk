import SwiftUI
import ListwerkCore

@main
struct ListwerkApp: App {
    @State private var model: AppModel

    init() {
        AppModel.applyStoredLanguage()
        let m = AppModel()
        _model = State(initialValue: m)
        #if os(macOS)
        QuickEntryPanel.install(model: m)
        #endif
    }

    var body: some Scene {
        WindowGroup(id: "main") {
            RootView()
                .environment(model)
                .task { await model.syncNow() }
        }
        #if os(macOS)
        .commands { SidebarCommands() }
        #endif
        #if os(macOS)
        Settings {
            SettingsView().environment(model)
        }
        MenuBarExtra("ToDo-Listwerk", systemImage: "checklist") {
            MenuBarView().environment(model)
        }
        .menuBarExtraStyle(.window)
        #endif
    }
}
