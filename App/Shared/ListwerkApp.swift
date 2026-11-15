import SwiftUI
import ListwerkCore

@main
struct ListwerkApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
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
        #endif
    }
}
