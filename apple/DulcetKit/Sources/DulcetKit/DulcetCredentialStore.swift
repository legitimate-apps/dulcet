import Foundation
import Security

@MainActor
public protocol DulcetCredentialStoring: AnyObject {
    func load() throws -> DulcetAccountConnectRequest?
    func save(_ request: DulcetAccountConnectRequest) throws
    func delete() throws
}

/// Credential stores that persist the locally minted provider-instance identity with the account.
@MainActor
public protocol DulcetProviderInstanceCredentialStoring: DulcetCredentialStoring {
    var providerInstanceID: String? { get }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws
}

public enum DulcetCredentialStoreError: Error, Equatable {
    case credentialMissing
    case malformedRecord
    case missingDataProtectionKeychainEntitlement
    case unexpectedStatus(OSStatus)
}

/// One active account stored as a generic-password item keyed by a non-secret local UUID.
@MainActor
public final class DulcetKeychainCredentialStore: DulcetProviderInstanceCredentialStoring {
    public static let productionService = "com.legitimateapps.dulcet"

    private let service: String
    private let defaults: UserDefaults
    private let activeAccountKey: String

    public init(
        service: String = DulcetKeychainCredentialStore.productionService,
        defaults: UserDefaults = .standard,
        activeAccountKey: String = "com.legitimateapps.dulcet.active-account-id"
    ) {
        self.service = service
        self.defaults = defaults
        self.activeAccountKey = activeAccountKey
    }

    public func load() throws -> DulcetAccountConnectRequest? {
        guard let accountID = defaults.string(forKey: activeAccountKey) else {
            return nil
        }
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
        return record.request
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
        precondition(!providerInstanceID.isEmpty)
        let accountID = providerInstanceID
        let data = try JSONEncoder().encode(CredentialRecord(request))
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
        defaults.set(accountID, forKey: activeAccountKey)
    }

    public func delete() throws {
        guard let accountID = defaults.string(forKey: activeAccountKey) else { return }
        let status = SecItemDelete(baseQuery(accountID: accountID) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw storeError(for: status)
        }
        defaults.removeObject(forKey: activeAccountKey)
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

    init(_ request: DulcetAccountConnectRequest) {
        serverURL = request.serverURL
        username = request.username
        password = request.password
        allowLocalHTTP = request.allowLocalHTTP
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
