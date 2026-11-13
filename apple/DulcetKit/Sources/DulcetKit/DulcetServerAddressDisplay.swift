import Foundation

/// The server address a person reads on the account page: the host, a port other than the
/// scheme's default, and any path the server is mounted under. Credentials, query and fragment
/// never appear, because a `/rest` URL can carry them in its query string.
enum DulcetServerAddressDisplay {
    static func text(_ address: String) -> String {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              let host = components.host, !host.isEmpty else {
            return fallback(trimmed)
        }
        var shown = host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
        if let port = components.port, port != defaultPort(components.scheme) {
            shown += ":\(port)"
        }
        let path = components.percentEncodedPath
        if !path.isEmpty, path != "/" {
            shown += path.hasSuffix("/") ? String(path.dropLast()) : path
        }
        return shown
    }

    /// The same text with a line-break opportunity after each ".", ":" and "/", so a wrapped
    /// address breaks between its parts rather than inside a number ("127.0.0.1:49 / 322").
    static func breakable(_ text: String) -> String {
        var result = ""
        for character in text {
            result.append(character)
            if character == "." || character == ":" || character == "/" { result.append("\u{200B}") }
        }
        return result
    }

    private static func defaultPort(_ scheme: String?) -> Int? {
        switch scheme?.lowercased() {
        case "https": 443
        case "http": 80
        default: nil
        }
    }

    /// Text that does not parse as a URL is cut before any query or fragment and after any
    /// user information, so a malformed value still shows no credential.
    private static func fallback(_ text: String) -> String {
        var rest = Substring(text)
        if let schemeEnd = rest.range(of: "://") { rest = rest[schemeEnd.upperBound...] }
        if let end = rest.firstIndex(where: { $0 == "?" || $0 == "#" }) { rest = rest[..<end] }
        if let at = rest.lastIndex(of: "@") { rest = rest[rest.index(after: at)...] }
        return String(rest)
    }
}
