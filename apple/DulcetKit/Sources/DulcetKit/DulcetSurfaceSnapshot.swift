import Foundation

/// A small, credential-free projection for WidgetKit. Never shares the reader database or Keychain.
public struct DulcetSurfaceSnapshot: Codable, Equatable, Sendable {
    public var title: String
    public var subtitle: String
    public var isPlaying: Bool
    public var updatedAt: Date
    public var recent: [Item]
    public struct Item: Codable, Equatable, Sendable, Identifiable {
        public var id: String
        public var title: String
        public var providerID: String?
        public var rawID: String?
        public init(id: String, title: String, providerID: String? = nil, rawID: String? = nil) {
            self.id = id; self.title = title; self.providerID = providerID; self.rawID = rawID
        }
    }
    public init(title: String = "Dulcet", subtitle: String = "Open your music library", isPlaying: Bool = false, updatedAt: Date = Date(), recent: [Item] = []) {
        self.title = title; self.subtitle = subtitle; self.isPlaying = isPlaying
        self.updatedAt = updatedAt; self.recent = recent
    }
    public static func groupID(bundleID: String) -> String {
        "group." + bundleID.replacingOccurrences(of: ".widgets", with: "")
    }
    public static func read(from defaults: UserDefaults?) -> Self {
        guard let data = defaults?.data(forKey: "dulcet.surface.snapshot"),
              let value = try? JSONDecoder().decode(Self.self, from: data) else { return .init() }
        return value
    }
    public func write(to defaults: UserDefaults?) {
        guard let data = try? JSONEncoder().encode(self) else { return }
        defaults?.set(data, forKey: "dulcet.surface.snapshot")
    }
}
