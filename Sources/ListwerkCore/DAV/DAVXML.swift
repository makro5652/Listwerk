import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

enum NS {
    static let dav = "DAV:"
    static let caldav = "urn:ietf:params:xml:ns:caldav"
    static let apple = "http://apple.com/ns/ical/"
    static let cs = "http://calendarserver.org/ns/"
    static let oc = "http://owncloud.org/ns"
}

/// Minimaler XML-Baum mit Namensräumen, genug für WebDAV-Antworten.
final class XMLNode {
    let ns: String
    let name: String
    var attributes: [String: String] = [:]
    var text = ""
    var children: [XMLNode] = []

    init(ns: String, name: String) { self.ns = ns; self.name = name }

    func child(_ ns: String, _ name: String) -> XMLNode? { children.first { $0.ns == ns && $0.name == name } }
    func children(_ ns: String, _ name: String) -> [XMLNode] { children.filter { $0.ns == ns && $0.name == name } }
    var trimmedText: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    static func parse(_ data: Data) throws -> XMLNode {
        let builder = Builder()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true
        parser.delegate = builder
        guard parser.parse(), let root = builder.root else {
            throw DAVError.invalidResponse("XML nicht lesbar: \(parser.parserError?.localizedDescription ?? "unbekannt")")
        }
        return root
    }

    private final class Builder: NSObject, XMLParserDelegate {
        var root: XMLNode?
        var stack: [XMLNode] = []

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
            let local = elementName.split(separator: ":").last.map(String.init) ?? elementName
            let node = XMLNode(ns: namespaceURI ?? "", name: local)
            node.attributes = attributeDict
            if let parent = stack.last { parent.children.append(node) } else { root = node }
            stack.append(node)
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            _ = stack.popLast()
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) { stack.last?.text += string }
        func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
            stack.last?.text += String(decoding: CDATABlock, as: UTF8.self)
        }
    }
}

/// Eine `<response>` aus einem Multistatus.
struct DAVResponse {
    var href: String
    /// Status direkt an der Response (z. B. 404 bei gelöschten Ressourcen im sync-collection-Report).
    var status: Int?
    /// Properties mit Status 2xx.
    var props: XMLNode?

    func prop(_ ns: String, _ name: String) -> XMLNode? { props?.child(ns, name) }
}

struct Multistatus {
    var responses: [DAVResponse]
    var syncToken: String?

    static func parse(_ data: Data) throws -> Multistatus {
        let root = try XMLNode.parse(data)
        guard root.ns == NS.dav, root.name == "multistatus" else {
            throw DAVError.invalidResponse("Kein Multistatus: \(root.name)")
        }
        var out: [DAVResponse] = []
        for r in root.children(NS.dav, "response") {
            guard let hrefNode = r.child(NS.dav, "href") else { continue }
            let combined = XMLNode(ns: NS.dav, name: "prop")
            for ps in r.children(NS.dav, "propstat") {
                let code = ps.child(NS.dav, "status").flatMap { statusCode($0.trimmedText) } ?? 200
                if (200..<300).contains(code), let p = ps.child(NS.dav, "prop") { combined.children += p.children }
            }
            out.append(DAVResponse(
                href: normalizeHref(hrefNode.trimmedText),
                status: r.child(NS.dav, "status").flatMap { statusCode($0.trimmedText) },
                props: combined
            ))
        }
        return Multistatus(responses: out, syncToken: root.child(NS.dav, "sync-token")?.trimmedText)
    }

    /// `HTTP/1.1 207 Multi-Status` → 207
    static func statusCode(_ line: String) -> Int? {
        let parts = line.split(separator: " ")
        return parts.count >= 2 ? Int(parts[1]) : nil
    }

    /// Absolute URLs werden auf den (prozentkodierten) Pfad gekürzt.
    static func normalizeHref(_ href: String) -> String {
        if href.hasPrefix("http://") || href.hasPrefix("https://"), let c = URLComponents(string: href) {
            return c.percentEncodedPath
        }
        return href
    }
}

enum XMLText {
    static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }
}
