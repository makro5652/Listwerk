// Erzeugt die App-Icons: swift scripts/make_icon.swift
// Ausgabe: App/macOS/Assets.xcassets/AppIcon.appiconset, App/iPad/Assets.xcassets/AppIcon.appiconset
import AppKit

func render(size: Int, mac: Bool) -> Data {
    let s = CGFloat(size)
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    // macOS: Kachel mit Rand (Raster 824/1024), iOS: randlos, das System rundet ab.
    let inset = mac ? s * 0.098 : 0
    let r = NSRect(x: inset, y: inset, width: s - 2 * inset, height: s - 2 * inset)
    let path = NSBezierPath(roundedRect: r, xRadius: mac ? r.width * 0.225 : 0, yRadius: mac ? r.width * 0.225 : 0)
    // Catppuccin Mocha: blue -> mauve
    NSGradient(starting: NSColor(srgbRed: 0.54, green: 0.71, blue: 0.98, alpha: 1),
               ending: NSColor(srgbRed: 0.80, green: 0.65, blue: 0.97, alpha: 1))!.draw(in: path, angle: -60)
    let u = r.width / 100, ox = r.minX, oy = r.minY
    let ink = NSColor(srgbRed: 0.12, green: 0.12, blue: 0.18, alpha: 1) // crust
    // Drei Listenzeilen, die oberste mit Haken
    ink.setStroke()
    for (i, y) in [68.0, 48.0, 28.0].enumerated() {
        if i > 0 {
            let box = NSBezierPath(ovalIn: NSRect(x: ox + 22 * u, y: oy + (y - 6) * u, width: 12 * u, height: 12 * u))
            box.lineWidth = 3 * u; box.stroke()
        }
        let line = NSBezierPath()
        line.move(to: NSPoint(x: ox + 44 * u, y: oy + y * u)); line.line(to: NSPoint(x: ox + 80 * u, y: oy + y * u))
        line.lineWidth = 5 * u; line.lineCapStyle = .round; line.stroke()
    }
    let chk = NSBezierPath()
    chk.move(to: NSPoint(x: ox + 24 * u, y: oy + 68 * u)); chk.line(to: NSPoint(x: ox + 28 * u, y: oy + 63 * u))
    chk.line(to: NSPoint(x: ox + 36 * u, y: oy + 76 * u))
    chk.lineWidth = 5 * u; chk.lineCapStyle = .round; chk.lineJoinStyle = .round; chk.stroke()
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

func write(_ dir: String, _ files: [(String, Int)], mac: Bool, json: String) throws {
    try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
    for (name, px) in files { try render(size: px, mac: mac).write(to: URL(fileURLWithPath: "\(dir)/\(name)")) }
    try json.write(toFile: "\(dir)/Contents.json", atomically: true, encoding: .utf8)
}

let macSizes = [(16, 1), (16, 2), (32, 1), (32, 2), (128, 1), (128, 2), (256, 1), (256, 2), (512, 1), (512, 2)]
let macImages = macSizes.map { "{\"idiom\":\"mac\",\"size\":\"\($0.0)x\($0.0)\",\"scale\":\"\($0.1)x\",\"filename\":\"icon_\($0.0)@\($0.1)x.png\"}" }
try write("App/macOS/Assets.xcassets/AppIcon.appiconset", macSizes.map { ("icon_\($0.0)@\($0.1)x.png", $0.0 * $0.1) },
          mac: true, json: "{\"images\":[\(macImages.joined(separator: ","))],\"info\":{\"version\":1,\"author\":\"xcode\"}}")
try write("App/iPad/Assets.xcassets/AppIcon.appiconset", [("icon_1024.png", 1024)], mac: false,
          json: "{\"images\":[{\"idiom\":\"universal\",\"platform\":\"ios\",\"size\":\"1024x1024\",\"filename\":\"icon_1024.png\"}],\"info\":{\"version\":1,\"author\":\"xcode\"}}")
for d in ["App/macOS", "App/iPad"] {
    try "{\"info\":{\"version\":1,\"author\":\"xcode\"}}".write(toFile: "\(d)/Assets.xcassets/Contents.json", atomically: true, encoding: .utf8)
}
