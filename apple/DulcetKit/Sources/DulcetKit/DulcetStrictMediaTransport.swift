import Foundation
import CFNetwork
import Network
import Security

/// Media sessions require the terminating HTTP chunk. Some system URL loading implementations
/// report success when the peer closes between chunks, so decoded EOF alone is insufficient.
public enum DulcetMediaTransport {
    public static func requireCompleteHTTPBodies(in configuration: URLSessionConfiguration) {
        let defaults = URLSessionConfiguration.ephemeral.protocolClasses ?? []
        let custom = (configuration.protocolClasses ?? []).filter { candidate in
            candidate != DulcetStrictMediaURLProtocol.self && !defaults.contains { $0 == candidate }
        }
        let explicitProxy = configuration.connectionProxyDictionary.map { settings in
            ["HTTPEnable", "HTTPSEnable", "SOCKSEnable", "ProxyAutoConfigEnable", "ProxyAutoDiscoveryEnable"]
                .contains { (settings[$0] as? NSNumber)?.boolValue == true }
        } ?? false
        let transport: AnyClass = explicitProxy ? DulcetUnsupportedMediaProxyProtocol.self : DulcetStrictMediaURLProtocol.self
        configuration.protocolClasses = custom + [transport] + defaults
    }
}

private final class DulcetUnsupportedMediaProxyProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool {
        ["http", "https"].contains(request.url?.scheme?.lowercased() ?? "")
    }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost)) }
    override func stopLoading() {}
}

private final class DulcetStrictMediaURLProtocol: URLProtocol, @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.legitimateapps.dulcet.media-framing")
    private var connection: NWConnection?
    private var timeout: DispatchWorkItem?
    private var timeoutGeneration = 0
    private var finished = false
    private var deliveredResponse = false
    private var parser = StrictMediaHTTPParser()

    override class func canInit(with request: URLRequest) -> Bool {
        ["http", "https"].contains(request.url?.scheme?.lowercased() ?? "")
    }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { queue.async { self.start() } }
    override func stopLoading() { queue.async { self.finish(error: URLError(.cancelled)) } }

    private func start() {
        guard let url = request.url, let urlHost = url.host,
              let rawPort = UInt16(exactly: url.port ?? (url.scheme == "https" ? 443 : 80)),
              let port = NWEndpoint.Port(rawValue: rawPort),
              request.httpMethod == nil || request.httpMethod == "GET" else {
            finish(error: URLError(.unsupportedURL)); return
        }
        let host = urlHost.hasPrefix("[") && urlHost.hasSuffix("]") ? String(urlHost.dropFirst().dropLast()) : urlHost
        // A direct socket must not silently bypass an operator's proxy policy. Proxy-backed
        // media requires a transport that can validate both the proxy and HTTP body framing.
        if let settings = CFNetworkCopySystemProxySettings()?.takeRetainedValue(),
           let proxies = CFNetworkCopyProxiesForURL(url as CFURL, settings).takeRetainedValue() as? [[String: Any]],
           let proxy = proxies.first, let type = proxy[kCFProxyTypeKey as String] as? String,
           type != kCFProxyTypeNone as String {
            finish(error: URLError(.cannotConnectToHost)); return
        }
        let parameters: NWParameters
        if url.scheme == "https" {
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_add_tls_application_protocol(tls.securityProtocolOptions, "http/1.1")
            parameters = NWParameters(tls: tls)
        } else { parameters = .tcp }
        parameters.prohibitExpensivePaths = !request.allowsExpensiveNetworkAccess
        parameters.prohibitConstrainedPaths = !request.allowsConstrainedNetworkAccess
        if !request.allowsCellularAccess { parameters.prohibitedInterfaceTypes = [.cellular] }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: parameters)
        self.connection = connection
        refreshIdleTimeout()
        connection.stateUpdateHandler = { [weak self] state in
            guard let self, !finished else { return }
            switch state {
            case .ready:
                let components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
                let path = components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath
                let target = path + (components.percentEncodedQuery.map { "?" + $0 } ?? "")
                let authority = (host.contains(":") ? "[\(host)]" : host) + (url.port.map { ":\($0)" } ?? "")
                var headers = request.allHTTPHeaderFields ?? [:]
                let hostHeader = request.value(forHTTPHeaderField: "Host") ?? authority
                guard !hostHeader.contains("\r"), !hostHeader.contains("\n") else { finish(error: URLError(.badURL)); return }
                for name in headers.keys where ["host", "connection", "accept-encoding"].contains(name.lowercased()) {
                    headers.removeValue(forKey: name)
                }
                var wire = "GET \(target) HTTP/1.1\r\nHost: \(hostHeader)\r\nConnection: close\r\nAccept-Encoding: identity\r\n"
                for (name, value) in headers {
                    guard !name.contains("\r"), !name.contains("\n"), !value.contains("\r"), !value.contains("\n") else {
                        finish(error: URLError(.badURL)); return
                    }
                    wire += "\(name): \(value)\r\n"
                }
                wire += "\r\n"
                connection.send(content: Data(wire.utf8), completion: .contentProcessed { [weak self] error in
                    guard let self else { return }
                    if error != nil { finish(error: URLError(.networkConnectionLost)) } else { refreshIdleTimeout(); receive() }
                })
            case let .failed(error):
                if case .tls = error { finish(error: URLError(.secureConnectionFailed)) }
                else { finish(error: URLError(.networkConnectionLost)) }
            default: break
            }
        }
        connection.start(queue: queue)
    }

    private func refreshIdleTimeout() {
        timeout?.cancel()
        timeoutGeneration += 1
        let generation = timeoutGeneration
        let work = DispatchWorkItem { [weak self] in
            guard let self, generation == timeoutGeneration else { return }
            finish(error: URLError(.timedOut))
        }
        timeout = work
        queue.asyncAfter(deadline: .now() + max(0.01, request.timeoutInterval), execute: work)
    }

    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, eof, error in
            guard let self, !finished else { return }
            do {
                if let data, !data.isEmpty { refreshIdleTimeout(); try parser.append(data) }
                deliverReceivedBytes()
                guard !finished else { return }
                if parser.complete { finish(error: nil) }
                else if error != nil { finish(error: URLError(.networkConnectionLost)) }
                else if eof {
                    try parser.endOfConnection()
                    finish(error: nil)
                } else { receive() }
            } catch { finish(error: URLError(.networkConnectionLost)) }
        }
    }

    private func deliverReceivedBytes() {
        if !deliveredResponse, parser.status >= 200 {
            guard let url = request.url, let response = HTTPURLResponse(url: url, statusCode: parser.status,
                httpVersion: "HTTP/1.1", headerFields: parser.headers) else {
                finish(error: URLError(.badServerResponse)); return
            }
            if [301, 302, 303, 307, 308].contains(parser.status),
               let location = parser.header("location"), let target = URL(string: location, relativeTo: url)?.absoluteURL {
                var redirected = request
                redirected.url = target
                let scheme = url.scheme?.lowercased()
                let targetScheme = target.scheme?.lowercased()
                let sourcePort = url.port ?? (scheme == "https" ? 443 : 80)
                let targetPort = target.port ?? (targetScheme == "https" ? 443 : 80)
                if url.host?.lowercased() != target.host?.lowercased() || sourcePort != targetPort || scheme != targetScheme {
                    redirected.setValue(nil, forHTTPHeaderField: "Host")
                    redirected.setValue(nil, forHTTPHeaderField: "Authorization")
                    redirected.setValue(nil, forHTTPHeaderField: "Cookie")
                }
                client?.urlProtocol(self, wasRedirectedTo: redirected, redirectResponse: response)
                // A declined redirect still completes its original response. URLSession
                // invalidates this protocol when it follows the proposed request.
            }
            deliveredResponse = true
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        }
        if deliveredResponse, !parser.body.isEmpty {
            client?.urlProtocol(self, didLoad: parser.body)
            parser.body.removeAll(keepingCapacity: false)
        }
    }

    private func finish(error: Error?, notify: Bool = true) {
        guard !finished else { return }
        finished = true
        timeout?.cancel()
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        guard notify else { return }
        if let error { client?.urlProtocol(self, didFailWithError: error) }
        else { client?.urlProtocolDidFinishLoading(self) }
    }
}

private struct StrictMediaHTTPParser {
    private enum Framing { case headers, length(Int), chunkSize, chunkBody(Int), chunkCRLF, trailers, close, done }
    private var framing = Framing.headers
    private var pending = Data()
    private var trailerBytes = 0
    private var informationalResponses = 0
    var status = 0
    var headers: [String: String] = [:]
    var body = Data()
    var complete: Bool { if case .done = framing { true } else { false } }
    func header(_ name: String) -> String? { headers.first { $0.key.lowercased() == name }?.value }
    private enum Invalid: Error { case framing }
    private static func isHeaderName(_ name: String) -> Bool {
        !name.isEmpty && name.utf8.allSatisfy { byte in
            (48 ... 57).contains(byte) || (65 ... 90).contains(byte) || (97 ... 122).contains(byte)
                || "!#$%&'*+-.^_`|~".utf8.contains(byte)
        }
    }

    mutating func append(_ data: Data) throws {
        pending.append(data)
        while true {
            switch framing {
            case .headers:
                guard let end = pending.range(of: Data("\r\n\r\n".utf8)) else {
                    guard pending.count <= 65_536 else { throw Invalid.framing }; return
                }
                guard pending.distance(from: pending.startIndex, to: end.upperBound) <= 65_536 else { throw Invalid.framing }
                let lines = String(decoding: pending[..<end.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
                let first = lines[0].split(separator: " ")
                guard first.count >= 2, ["HTTP/1.0", "HTTP/1.1"].contains(String(first[0])),
                      first[1].count == 3, let status = Int(first[1]), (100 ... 599).contains(status), status != 101 else { throw Invalid.framing }
                self.status = status
                headers = [:]
                for line in lines.dropFirst() {
                    guard let colon = line.firstIndex(of: ":") else { throw Invalid.framing }
                    let name = String(line[..<colon]).lowercased()
                    guard Self.isHeaderName(name) else { throw Invalid.framing }
                    let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                    if let previous = headers[name] {
                        guard name != "content-length", name != "transfer-encoding" else { throw Invalid.framing }
                        headers[name] = previous + ", " + value
                    } else { headers[name] = value }
                }
                pending.removeSubrange(..<end.upperBound)
                if (100..<200).contains(status) {
                    informationalResponses += 1
                    guard informationalResponses <= 16 else { throw Invalid.framing }
                    framing = .headers; continue
                }
                guard header("content-encoding").map({ $0.lowercased() == "identity" }) ?? true else { throw Invalid.framing }
                if status == 204 || status == 304 { framing = .done }
                else if let transfer = header("transfer-encoding") {
                    guard transfer.lowercased() == "chunked", header("content-length") == nil else { throw Invalid.framing }
                    framing = .chunkSize
                } else if let length = header("content-length") {
                    guard !length.isEmpty, length.utf8.allSatisfy({ (48 ... 57).contains($0) }),
                          let count = Int(length), count >= 0 else { throw Invalid.framing }
                    framing = .length(count)
                } else { framing = .close }
            case let .length(remaining):
                let count = min(remaining, pending.count)
                body.append(pending.prefix(count)); pending.removeFirst(count)
                if count == remaining { framing = .done } else { framing = .length(remaining - count); return }
            case .chunkSize:
                guard let end = pending.range(of: Data("\r\n".utf8)) else {
                    guard pending.count <= 65_536 else { throw Invalid.framing }; return
                }
                let line = String(decoding: pending[..<end.lowerBound], as: UTF8.self)
                let size = line.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)[0]
                guard !size.isEmpty, size.allSatisfy({ $0.isHexDigit }), let count = Int(size, radix: 16) else { throw Invalid.framing }
                pending.removeSubrange(..<end.upperBound)
                framing = count == 0 ? .trailers : .chunkBody(count)
            case let .chunkBody(remaining):
                let count = min(remaining, pending.count)
                body.append(pending.prefix(count)); pending.removeFirst(count)
                if count == remaining { framing = .chunkCRLF } else { framing = .chunkBody(remaining - count); return }
            case .chunkCRLF:
                guard pending.count >= 2 else { return }
                guard pending.prefix(2) == Data("\r\n".utf8) else { throw Invalid.framing }
                pending.removeFirst(2); framing = .chunkSize
            case .trailers:
                guard let end = pending.range(of: Data("\r\n".utf8)) else {
                    guard pending.count <= 65_536 else { throw Invalid.framing }; return
                }
                trailerBytes += pending.distance(from: pending.startIndex, to: end.upperBound)
                guard trailerBytes <= 65_536 else { throw Invalid.framing }
                if end.lowerBound == pending.startIndex { pending.removeFirst(2); framing = .done }
                else {
                    let line = String(decoding: pending[..<end.lowerBound], as: UTF8.self)
                    guard let colon = line.firstIndex(of: ":"), Self.isHeaderName(String(line[..<colon])) else { throw Invalid.framing }
                    pending.removeSubrange(..<end.upperBound)
                }
            case .close:
                body.append(pending); pending.removeAll(keepingCapacity: true); return
            case .done: return
            }
        }
    }
    mutating func endOfConnection() throws {
        if case .close = framing { framing = .done }
        guard complete else { throw Invalid.framing }
    }
}
