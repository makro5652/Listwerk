import SwiftUI
import AppKit
import Carbon.HIToolbox
import ListwerkCore

/// Inhalt des Menüleisten-Symbols: Schnelleingabe und heute Fälliges zum Abhaken.
struct MenuBarView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        let today = ((try? model.store.allTasks()) ?? [])
            .filter { $0.item.parentUID == nil && !$0.item.isCompleted && model.matches(.myDay, $0) }
        VStack(alignment: .leading, spacing: 10) {
            QuickEntryView()
            Divider()
            Text(today.isEmpty ? "Heute nichts fällig." : "Heute und überfällig").font(.headline)
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(today.prefix(30)) { rec in
                        HStack(alignment: .firstTextBaseline) {
                            Button { model.toggleDone(rec) } label: { Image(systemName: "circle") }.buttonStyle(.plain)
                            Text(rec.item.title).lineLimit(2)
                            Spacer()
                            Text(model.list(rec.listHref)?.name ?? "").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .frame(maxHeight: 260)
            Divider()
            HStack {
                Button("Listwerk öffnen") {
                    openWindow(id: "main")
                    NSApp.activate(ignoringOtherApps: true)
                }
                Spacer()
                Text("⌃⌥N: Schnelleingabe").font(.caption).foregroundStyle(.secondary)
                Button("Beenden") { NSApp.terminate(nil) }
            }
        }
        .padding(12)
        .frame(width: 380)
    }
}

/// Globales Kürzel ⌃⌥N öffnet überall ein kleines Eingabefenster. Carbon-Hotkeys brauchen keine
/// Bedienungshilfen-Freigabe.
@MainActor
final class QuickEntryPanel {
    private static var shared: QuickEntryPanel?
    private let model: AppModel
    private var panel: NSPanel?
    private var hotKey: EventHotKeyRef?
    private var wasActive = false

    static func install(model: AppModel) {
        guard shared == nil else { return }
        let p = QuickEntryPanel(model: model)
        shared = p
        p.register()
    }

    private init(model: AppModel) { self.model = model }

    private func register() {
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, _, _ in
            DispatchQueue.main.async { MainActor.assumeIsolated { QuickEntryPanel.shared?.toggle() } }
            return noErr
        }, 1, &spec, nil, nil)
        let id = EventHotKeyID(signature: OSType(0x4C57_524B), id: 1)   // 'LWRK'
        let status = RegisterEventHotKey(UInt32(kVK_ANSI_N), UInt32(controlKey | optionKey), id, GetApplicationEventTarget(), 0, &hotKey)
        if status != noErr { model.statusMessage = "Kürzel ⌃⌥N ist schon belegt." }
    }

    private func toggle() {
        if let panel, panel.isVisible { close(); return }
        wasActive = NSApp.isActive
        let p = panel ?? makePanel()
        panel = p
        p.center()
        NSApp.activate(ignoringOtherApps: true)
        p.makeKeyAndOrderFront(nil)
    }

    private func makePanel() -> NSPanel {
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 520, height: 90),
                        styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered, defer: true)
        p.titleVisibility = .hidden
        p.titlebarAppearsTransparent = true
        p.isMovableByWindowBackground = true
        p.level = .floating
        p.isReleasedWhenClosed = false
        p.hidesOnDeactivate = true
        let root = QuickEntryView(onDone: { [weak self] in self?.close() })
            .padding(16).padding(.top, 6)
            .frame(width: 520)
            .environment(model)
        p.contentViewController = NSHostingController(rootView: root)
        return p
    }

    private func close() {
        panel?.orderOut(nil)
        // Wer aus einer anderen App kam, landet wieder dort.
        if !wasActive { NSApp.hide(nil) }
    }
}
