import Foundation
import Security
import XCTest
import DulcetKit

#if os(iOS)
import UIKit
#endif

@MainActor
final class DulcetKeychainAttributeTests: XCTestCase {
#if os(iOS)
    func testProductionSaveRecordsDeviceOnlyNonSynchronizableAttributesOnIOSSimulator() throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.legitimateapps.dulcet.ios.dev")
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .phone)
        try assertProductionSaveRecordsExpectedAttributes()
    }

    func testProductionSaveRecordsDeviceOnlyNonSynchronizableAttributesOnIPadOSSimulator() throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.legitimateapps.dulcet.ios.dev")
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .pad)
        try assertProductionSaveRecordsExpectedAttributes()
    }
#elseif os(tvOS)
    func testProductionSaveRecordsDeviceOnlyNonSynchronizableAttributesOnTVOSSimulator() throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.legitimateapps.dulcet.tvos.dev")
        try assertProductionSaveRecordsExpectedAttributes()
    }
#endif

    private func assertProductionSaveRecordsExpectedAttributes() throws {
        let identifier = UUID().uuidString
        let suiteName = "com.legitimateapps.dulcet.keychain-tests.\(identifier)"
        let productionService = "com.legitimateapps.dulcet.keychain-tests.production.\(identifier)"
        let controlService = "com.legitimateapps.dulcet.keychain-tests.control.\(identifier)"
        let activeAccountKey = "active-account"
        let controlAccount = "wrong-accessibility-control"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let store = DulcetKeychainCredentialStore(
            service: productionService,
            defaults: defaults,
            activeAccountKey: activeAccountKey
        )
        defer {
            try? store.delete()
            SecItemDelete(deleteQuery(service: productionService) as CFDictionary)
            SecItemDelete(deleteQuery(service: controlService) as CFDictionary)
            defaults.removePersistentDomain(forName: suiteName)
        }

        let controlAddStatus = SecItemAdd([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: controlService,
            kSecAttrAccount as String: controlAccount,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
            kSecValueData as String: Data("attribute-readback-control".utf8),
        ] as CFDictionary, nil)
        XCTAssertEqual(controlAddStatus, errSecSuccess)

        let controlQuery = attributeQuery(service: controlService, account: controlAccount)
        XCTAssertNil(controlQuery[kSecAttrAccessible as String])
        XCTAssertEqual(
            controlQuery[kSecAttrSynchronizable as String] as? String,
            kSecAttrSynchronizableAny as String
        )
        let controlAttributes = try copyAttributes(using: controlQuery)
        XCTAssertEqual(
            controlAttributes[kSecAttrAccessible as String] as? String,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String
        )
        XCTAssertNotEqual(
            controlAttributes[kSecAttrAccessible as String] as? String,
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String
        )

        try store.save(DulcetAccountConnectRequest(
            serverURL: "https://music.example.invalid",
            username: "attribute-test-user",
            password: "attribute-test-password",
            allowLocalHTTP: false
        ))

        let savedAccount = try XCTUnwrap(
            defaults.string(forKey: activeAccountKey),
            "The production save path did not publish its active-account marker"
        )
        let savedQuery = attributeQuery(service: productionService, account: savedAccount)
        XCTAssertNil(savedQuery[kSecAttrAccessible as String])
        XCTAssertEqual(
            savedQuery[kSecAttrSynchronizable as String] as? String,
            kSecAttrSynchronizableAny as String
        )
        let savedAttributes = try copyAttributes(using: savedQuery)
        XCTAssertEqual(savedAttributes[kSecAttrService as String] as? String, productionService)
        XCTAssertEqual(savedAttributes[kSecAttrAccount as String] as? String, savedAccount)
        XCTAssertEqual(
            savedAttributes[kSecAttrAccessible as String] as? String,
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String
        )
        XCTAssertEqual(
            (savedAttributes[kSecAttrSynchronizable as String] as? NSNumber)?.boolValue,
            false
        )
    }

    private func attributeQuery(service: String, account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
            kSecReturnAttributes as String: kCFBooleanTrue as Any,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
    }

    private func copyAttributes(using query: [String: Any]) throws -> [String: Any] {
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        XCTAssertEqual(status, errSecSuccess)
        return try XCTUnwrap(result as? [String: Any])
    }

    private func deleteQuery(service: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
    }
}
