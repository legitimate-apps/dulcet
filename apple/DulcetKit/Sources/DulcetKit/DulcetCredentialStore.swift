import Foundation
import Security

@MainActor
public protocol DulcetCredentialStoring: AnyObject {
    var credentialGeneration: Int64 { get }
    func load() throws -> DulcetAccountConnectRequest?
    func save(_ request: DulcetAccountConnectRequest) throws
    func delete() throws
}

public extension DulcetCredentialStoring {
    var credentialGeneration: Int64 { 0 }
}

/// Credential stores that persist the locally minted provider-instance identity with the account.
@MainActor
public protocol DulcetProviderInstanceCredentialStoring: DulcetCredentialStoring {
    var providerInstanceID: String? { get }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws
}

/// Saved server identifiers are local UUIDs; credentials and identity evidence stay in Keychain.
@MainActor
public protocol DulcetSavedServerCredentialStoring: DulcetProviderInstanceCredentialStoring {
    var savedProviderInstanceIDs: [String] { get }
    var serverIdentity: String { get }
    func load(providerInstanceID: String) throws -> DulcetAccountConnectRequest
    func serverIdentity(providerInstanceID: String) throws -> String
    func activate(providerInstanceID: String) throws
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String, serverIdentity: String) throws
    func delete(providerInstanceID: String) throws
}

public enum DulcetCredentialStoreError: Error, Equatable {
    case credentialMissing
    case malformedRecord
    case missingDataProtectionKeychainEntitlement
    case unexpectedStatus(OSStatus)
}

/// One active account stored as a generic-password item keyed by a non-secret local UUID.
@MainActor
public final class DulcetKeychainCredentialStore: DulcetSavedServerCredentialStoring {
    public static let productionService = "com.legitimateapps.dulcet"

    private let service: String
    private let defaults: UserDefaults
    private let activeAccountKey: String
    public private(set) var credentialGeneration: Int64 = 0

    public var activeAccountID: String? {
        defaults.string(forKey: activeAccountKey)
    }

    public init(
        service: String = DulcetKeychainCredentialStore.productionService,
        defaults: UserDefaults = .standard,
        activeAccountKey: String = "com.legitimateapps.dulcet.active-account-id"
    ) {
        self.service = service
        self.defaults = defaults
        self.activeAccountKey = activeAccountKey
    }

    private var savedAccountsKey: String { activeAccountKey + ".saved" }
    public var savedProviderInstanceIDs: [String] {
        var ids = defaults.stringArray(forKey: savedAccountsKey) ?? []
        if let activeAccountID, !ids.contains(activeAccountID) { ids.append(activeAccountID) }
        return ids
    }
    public var serverIdentity: String {
        guard let activeAccountID else { return "" }
        return (try? serverIdentity(providerInstanceID: activeAccountID)) ?? ""
    }
    public func load(providerInstanceID: String) throws -> DulcetAccountConnectRequest {
        try loadRecord(accountID: providerInstanceID).request
    }
    public func serverIdentity(providerInstanceID: String) throws -> String {
        try loadRecord(accountID: providerInstanceID).serverIdentity
    }
    public func activate(providerInstanceID: String) throws {
        let record = try loadRecord(accountID: providerInstanceID)
        defaults.set(providerInstanceID, forKey: activeAccountKey)
        credentialGeneration = record.credentialGeneration
    }

    public func load() throws -> DulcetAccountConnectRequest? {
        guard let accountID = defaults.string(forKey: activeAccountKey) else {
            credentialGeneration = 0
            return nil
        }
        let record = try loadRecord(accountID: accountID)
        credentialGeneration = record.credentialGeneration
        return record.request
    }

    private func loadRecord(accountID: String) throws -> CredentialRecord {
        var result: CFTypeRef?
        var query = baseQuery(accountID: accountID)
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status != errSecItemNotFound else {
            throw DulcetCredentialStoreError.credentialMissing
        }
        guard status == errSecSuccess else {
            throw storeError(for: status)
        }
        guard let data = result as? Data,
              let record = try? JSONDecoder().decode(CredentialRecord.self, from: data) else {
            throw DulcetCredentialStoreError.malformedRecord
        }
        return record
    }

    public var providerInstanceID: String? {
        defaults.string(forKey: activeAccountKey)
    }

    public func save(_ request: DulcetAccountConnectRequest) throws {
        try save(
            request,
            providerInstanceID: defaults.string(forKey: activeAccountKey) ?? UUID().uuidString
        )
    }

    public func save(
        _ request: DulcetAccountConnectRequest,
        providerInstanceID: String
    ) throws {
        try save(request, providerInstanceID: providerInstanceID,
                 serverIdentity: (try? serverIdentity(providerInstanceID: providerInstanceID)) ?? "")
    }

    public func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String, serverIdentity: String) throws {
        precondition(!providerInstanceID.isEmpty)
        let accountID = providerInstanceID
        let previous = try? loadRecord(accountID: accountID)
        let nextGeneration: Int64
        if previous?.request == request {
            nextGeneration = previous?.credentialGeneration ?? 0
        } else if let previous, previous.credentialGeneration < Int64.max {
            nextGeneration = previous.credentialGeneration + 1
        } else {
            nextGeneration = 1
        }
        let data = try JSONEncoder().encode(CredentialRecord(
            request,
            credentialGeneration: nextGeneration, serverIdentity: serverIdentity
        ))
        let query = baseQuery(accountID: accountID)
        let update: [String: Any] = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(query as CFDictionary, update as CFDictionary)

        let finalStatus: OSStatus
        if updateStatus == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] =
                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            finalStatus = SecItemAdd(item as CFDictionary, nil)
        } else {
            finalStatus = updateStatus
        }
        guard finalStatus == errSecSuccess else {
            throw storeError(for: finalStatus)
        }
        var ids = savedProviderInstanceIDs
        if !ids.contains(accountID) { ids.append(accountID) }
        defaults.set(ids, forKey: savedAccountsKey)
        defaults.set(accountID, forKey: activeAccountKey)
        credentialGeneration = nextGeneration
    }

    public func delete() throws {
        guard let accountID = defaults.string(forKey: activeAccountKey) else {
            credentialGeneration = 0
            return
        }
        try delete(providerInstanceID: accountID)
    }

    public func delete(providerInstanceID accountID: String) throws {
        let status = SecItemDelete(baseQuery(accountID: accountID) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw storeError(for: status)
        }
        defaults.set(savedProviderInstanceIDs.filter { $0 != accountID }, forKey: savedAccountsKey)
        if activeAccountID == accountID {
            defaults.removeObject(forKey: activeAccountKey)
            credentialGeneration = 0
        }
    }

    private func baseQuery(accountID: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountID,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
    }

    private func storeError(for status: OSStatus) -> DulcetCredentialStoreError {
        if status == errSecMissingEntitlement {
            return .missingDataProtectionKeychainEntitlement
        }
        return .unexpectedStatus(status)
    }
}

// Every other type in this module that holds a password carries the same redaction trio, so that
// interpolating one cannot disclose it. CredentialRecord is `private` and is currently only encoded
// to and decoded from the Keychain, never logged — this is defence in depth, not a fix for a live
// leak. Without it, default synthesized reflection on a Codable struct prints every stored property,
// so the first `"\(record)"` anyone writes would disclose the password with no warning.
private struct CredentialRecord: Codable, CustomStringConvertible, CustomDebugStringConvertible,
                                 CustomReflectable {
    let serverURL: String
    let username: String
    let password: String
    let allowLocalHTTP: Bool
    let credentialGeneration: Int64
    let serverIdentity: String

    init(_ request: DulcetAccountConnectRequest, credentialGeneration: Int64, serverIdentity: String) {
        serverURL = request.serverURL
        username = request.username
        password = request.password
        allowLocalHTTP = request.allowLocalHTTP
        self.credentialGeneration = credentialGeneration
        self.serverIdentity = serverIdentity
    }

    private enum CodingKeys: String, CodingKey {
        case serverURL, username, password, allowLocalHTTP, credentialGeneration, serverIdentity
    }

    init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        serverURL = try values.decode(String.self, forKey: .serverURL)
        username = try values.decode(String.self, forKey: .username)
        password = try values.decode(String.self, forKey: .password)
        allowLocalHTTP = try values.decode(Bool.self, forKey: .allowLocalHTTP)
        serverIdentity = try values.decodeIfPresent(String.self, forKey: .serverIdentity) ?? ""
        credentialGeneration = try values.decodeIfPresent(
            Int64.self,
            forKey: .credentialGeneration
        ) ?? 0
    }

    var request: DulcetAccountConnectRequest {
        DulcetAccountConnectRequest(
            serverURL: serverURL,
            username: username,
            password: password,
            allowLocalHTTP: allowLocalHTTP
        )
    }

    var description: String { "CredentialRecord(<redacted>)" }

    var debugDescription: String { description }

    var customMirror: Mirror {
        Mirror(
            self,
            children: [("credentialRecord", "<redacted>" as Any)],
            displayStyle: .struct
        )
    }
}
