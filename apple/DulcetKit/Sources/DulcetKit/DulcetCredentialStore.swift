import Foundation
import Security

@MainActor
public protocol DulcetCredentialStoring: AnyObject {
    func load() throws -> DulcetAccountConnectRequest?
    func save(_ request: DulcetAccountConnectRequest) throws
    func delete() throws
}

public enum DulcetCredentialStoreError: Error, Equatable {
    case credentialMissing
    case malformedRecord
    case unexpectedStatus(OSStatus)
}

/// One active account stored as a generic-password item keyed by a non-secret local UUID.
@MainActor
public final class DulcetKeychainCredentialStore: DulcetCredentialStoring {
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
            throw DulcetCredentialStoreError.unexpectedStatus(status)
        }
        guard let data = result as? Data,
              let record = try? JSONDecoder().decode(CredentialRecord.self, from: data) else {
            throw DulcetCredentialStoreError.malformedRecord
        }
        return record.request
    }

    public func save(_ request: DulcetAccountConnectRequest) throws {
        let accountID = defaults.string(forKey: activeAccountKey) ?? UUID().uuidString
        let data = try JSONEncoder().encode(CredentialRecord(request))
        let query = baseQuery(accountID: accountID)
        let update: [String: Any] = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(query as CFDictionary, update as CFDictionary)

        let finalStatus: OSStatus
        if updateStatus == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            finalStatus = SecItemAdd(item as CFDictionary, nil)
        } else {
            finalStatus = updateStatus
        }
        guard finalStatus == errSecSuccess else {
            throw DulcetCredentialStoreError.unexpectedStatus(finalStatus)
        }
        defaults.set(accountID, forKey: activeAccountKey)
    }

    public func delete() throws {
        guard let accountID = defaults.string(forKey: activeAccountKey) else { return }
        let status = SecItemDelete(baseQuery(accountID: accountID) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw DulcetCredentialStoreError.unexpectedStatus(status)
        }
        defaults.removeObject(forKey: activeAccountKey)
    }

    private func baseQuery(accountID: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountID,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
        ]
    }
}

private struct CredentialRecord: Codable {
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
}
