import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct NextcloudCredentials: Equatable, Sendable {
    public var serverURL: URL
    public var loginName: String
    public var appPassword: String
}

/// Nextcloud Login Flow v2: Der Nutzer meldet sich im Browser an, die App erhält ein App-Passwort.
/// Das Hauptpasswort sieht die App nie.
public struct LoginFlowV2: Sendable {
    public struct Session: Sendable {
        public let loginURL: URL
        let pollEndpoint: URL
        let token: String
    }

    private let transport: HTTPTransport
    private let userAgent = "Listwerk/\(ListwerkCoreInfo.version)"

    public init(transport: HTTPTransport = URLSessionTransport()) { self.transport = transport }

    public func start(server: URL) async throws -> Session {
        var base = server
        if base.absoluteString.hasSuffix("/") { base = URL(string: String(base.absoluteString.dropLast()))! }
        var req = URLRequest(url: base.appendingPathComponent("index.php/login/v2"))
        req.httpMethod = "POST"
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await transport.send(req)
        guard resp.statusCode == 200 else {
            throw DAVError.http(status: resp.statusCode, detail: "Login Flow v2 nicht verfügbar")
        }
        struct Start: Decodable {
            struct Poll: Decodable { let token: String; let endpoint: String }
            let poll: Poll
            let login: String
        }
        let s = try JSONDecoder().decode(Start.self, from: data)
        guard let login = URL(string: s.login), let endpoint = URL(string: s.poll.endpoint) else {
            throw DAVError.invalidResponse("Ungültige Adressen im Login Flow")
        }
        return Session(loginURL: login, pollEndpoint: endpoint, token: s.poll.token)
    }

    /// Nil, solange der Nutzer die Anmeldung im Browser noch nicht bestätigt hat.
    public func poll(_ session: Session) async throws -> NextcloudCredentials? {
        var req = URLRequest(url: session.pollEndpoint)
        req.httpMethod = "POST"
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data("token=\(session.token)".utf8)
        let (data, resp) = try await transport.send(req)
        if resp.statusCode == 404 { return nil }
        guard resp.statusCode == 200 else { throw DAVError.http(status: resp.statusCode, detail: "Abfrage fehlgeschlagen") }
        struct Done: Decodable { let server: String; let loginName: String; let appPassword: String }
        let d = try JSONDecoder().decode(Done.self, from: data)
        guard let url = URL(string: d.server) else { throw DAVError.invalidResponse("Ungültige Server-Adresse") }
        return NextcloudCredentials(serverURL: url, loginName: d.loginName, appPassword: d.appPassword)
    }

    /// Fragt alle `interval` Sekunden nach, höchstens `timeout` Sekunden lang (Nextcloud lässt den Flow 20 Minuten gelten).
    public func waitForCredentials(_ session: Session, interval: TimeInterval = 2, timeout: TimeInterval = 1200) async throws -> NextcloudCredentials {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            try Task.checkCancellation()
            if let c = try await poll(session) { return c }
            try await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
        }
        throw DAVError.network("Zeitüberschreitung bei der Anmeldung")
    }
}
