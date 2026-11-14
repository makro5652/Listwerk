// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "Listwerk",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "ListwerkCore", targets: ["ListwerkCore"]),
    ],
    targets: [
        // SQLite: auf Apple-Plattformen liegt sqlite3.h im SDK, unter Linux in libsqlite3-dev.
        .systemLibrary(name: "CSQLite", path: "Sources/CSQLite"),
        .target(
            name: "ListwerkCore",
            dependencies: ["CSQLite"],
            resources: [.copy("Resources/themes"), .copy("Resources/palettes")]
        ),
        .testTarget(
            name: "ListwerkCoreTests",
            dependencies: ["ListwerkCore"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
