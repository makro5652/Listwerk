import SwiftUI
import CoreText
import UniformTypeIdentifiers
import ListwerkCore

/// Fertige Bytes (Sicherung oder PDF) für `fileExporter`.
struct ExportFile: FileDocument {
    static var readableContentTypes: [UTType] { [.json, .pdf] }
    var data: Data
    init(_ data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

/// Druckfertige Abhakliste: je Liste eine neue A4-Seite, vor jedem Eintrag ein leeres Kästchen.
enum PDFExport {
    struct Row { var item: TodoItem; var depth: Int }
    struct Section { var title: String; var rows: [Row] }

    /// Einträge mit ihren Unteraufgaben in Anzeigereihenfolge.
    static func rows(_ all: [TaskRecord], tops: [TaskRecord], includeDone: Bool) -> [Row] {
        func walk(_ r: TaskRecord, _ depth: Int) -> [Row] {
            guard includeDone || !r.item.isCompleted else { return [] }
            return [Row(item: r.item, depth: depth)]
                + all.filter { $0.item.parentUID == r.item.uid && $0.listHref == r.listHref }.flatMap { walk($0, depth + 1) }
        }
        return tops.flatMap { walk($0, 0) }
    }

    static func make(_ sections: [Section]) -> Data {
        var page = CGRect(x: 0, y: 0, width: 595, height: 842)   // A4 in Punkt
        let margin: CGFloat = 50, box: CGFloat = 11
        let out = NSMutableData()
        guard let consumer = CGDataConsumer(data: out),
              let ctx = CGContext(consumer: consumer, mediaBox: &page, nil) else { return Data() }
        var y: CGFloat = 0          // Abstand von oben
        var pageOpen = false
        func newPage() {
            if pageOpen { ctx.endPDFPage() }
            ctx.beginPDFPage(nil); pageOpen = true; y = margin
        }
        func text(_ s: String, size: CGFloat, bold: Bool = false, gray: Bool = false) -> NSAttributedString {
            let font = CTFontCreateWithName((bold ? "Helvetica-Bold" : "Helvetica") as CFString, size, nil)
            let color = CGColor(gray: gray ? 0.45 : 0, alpha: 1)
            return NSAttributedString(string: s, attributes: [
                NSAttributedString.Key(kCTFontAttributeName as String): font,
                NSAttributedString.Key(kCTForegroundColorAttributeName as String): color])
        }
        /// Zeichnet mehrzeiligen Text ab `y`; bricht vorher um, wenn er nicht mehr auf die Seite passt.
        /// `before` zeichnet z. B. das Kästchen auf derselben Seite wie den Text.
        func draw(_ s: NSAttributedString, x: CGFloat, after: CGFloat, before: ((CGFloat) -> Void)? = nil) {
            let w = page.width - margin - x
            let fs = CTFramesetterCreateWithAttributedString(s)
            let h = ceil(CTFramesetterSuggestFrameSizeWithConstraints(fs, CFRange(), nil, CGSize(width: w, height: .greatestFiniteMagnitude), nil).height)
            if y + h > page.height - margin { newPage() }
            before?(y)
            let path = CGPath(rect: CGRect(x: x, y: page.height - y - h, width: w, height: h), transform: nil)
            CTFrameDraw(CTFramesetterCreateFrame(fs, CFRange(), path, nil), ctx)
            y += h + after
        }

        let stand = L("Stand: {0}", Date().lwFormatted(date: .long, time: .shortened))
        for section in sections {
            newPage()
            draw(text(section.title, size: 20, bold: true), x: margin, after: 2)
            draw(text(stand, size: 9, gray: true), x: margin, after: 14)
            if section.rows.isEmpty { draw(text("Keine offenen Einträge.", size: 11, gray: true), x: margin, after: 0) }
            for row in section.rows {
                let i = row.item
                let x = margin + CGFloat(min(row.depth, 4)) * 20
                var line = (TodoPriority(icalValue: i.priority) == .high ? "! " : "") + (i.title.isEmpty ? L("Ohne Titel") : i.title)
                if let d = i.due {
                    line += "  ·  " + d.date().lwFormatted(date: .abbreviated, time: d.isAllDay ? .omitted : .shortened)
                }
                draw(text(line, size: 11, gray: i.isCompleted), x: x + box + 8, after: 9) { top in
                    let r = CGRect(x: x, y: page.height - top - box - 1.5, width: box, height: box)
                    ctx.setStrokeColor(gray: 0.2, alpha: 1); ctx.setLineWidth(0.8); ctx.stroke(r)
                    if i.isCompleted {   // Erledigtes vorab angekreuzt
                        ctx.move(to: CGPoint(x: r.minX + 2, y: r.midY)); ctx.addLine(to: CGPoint(x: r.minX + 4.5, y: r.minY + 2))
                        ctx.addLine(to: CGPoint(x: r.maxX - 1.5, y: r.maxY - 1.5)); ctx.strokePath()
                    }
                }
            }
        }
        if pageOpen { ctx.endPDFPage() }
        ctx.closePDF()
        return out as Data
    }
}

/// Werkzeugleisten-Knopf: aktuelle Ansicht als PDF zum Ausdrucken.
struct PDFExportButton: View {
    @Environment(AppModel.self) private var model
    @State private var file: ExportFile?

    var body: some View {
        Button { file = ExportFile(model.pdfCurrentView()) } label: { Label(L("Als PDF exportieren"), systemImage: "printer") }
            .help(L("Ansicht als PDF zum Ausdrucken und Abhaken"))
            .fileExporter(isPresented: Binding { file != nil } set: { if !$0 { file = nil } },
                          document: file, contentType: .pdf, defaultFilename: model.selectionTitle) { result in
                if case .failure(let e) = result { model.errorMessage = L("PDF speichern fehlgeschlagen: {0}", e.localizedDescription) }
            }
    }
}
