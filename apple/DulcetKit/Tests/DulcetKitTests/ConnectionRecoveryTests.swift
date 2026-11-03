import Testing
@testable import DulcetKit

@Test func httpAdoptedAccountVerifiesThroughItsAdmittedHTTPSInsteadOfItsFrozenAnchor() {
    let owner = DulcetConnectionOwnership(accountID: "fixture", anchorURL: "http://legacy.example.test/music",
        username: "listener", serverIdentity: "lineage")
    let home = DulcetConnectionRow(id: "home", address: "https://home.example.test/music", label: nil,
        enabled: true, removed: false, selected: true)
    let remote = DulcetConnectionRow(id: "remote", address: "https://remote.example.test/music", label: nil,
        enabled: true, removed: false, selected: false)
    let adopted = DulcetConnectionsState(ownership: owner, routes: [remote, home], enrolled: true, canUpgrade: true)
    #expect(adopted.verificationAddress == home.address)
    #expect(adopted.ownership == owner)
    let removedHome = DulcetConnectionRow(id: home.id, address: home.address, label: nil,
        enabled: false, removed: true, selected: false)
    let offline = DulcetConnectionsState(ownership: owner, routes: [removedHome, remote], enrolled: true, canUpgrade: true)
    #expect(offline.verificationAddress == remote.address)
    #expect(!offline.routes.contains { $0.selected })
    #expect(offline.ownership == owner)
    let removedAll = DulcetConnectionsState(ownership: owner, routes: [removedHome], enrolled: true, canUpgrade: true)
    #expect(removedAll.verificationAddress == nil)
}

@Test func unverifiedOrRemovedAndHTTPAddressesCannotBecomeAppleVerificationSources() {
    let owner = DulcetConnectionOwnership(accountID: "fixture", anchorURL: "http://legacy.example.test/music",
        username: "listener", serverIdentity: "lineage")
    let routes = [
        DulcetConnectionRow(id: "bad", address: "https://unverified.example.test", label: nil, enabled: false, removed: false, selected: true),
        DulcetConnectionRow(id: "removed", address: "https://removed.example.test", label: nil, enabled: true, removed: true, selected: false),
        DulcetConnectionRow(id: "http", address: owner.anchorURL, label: nil, enabled: true, removed: false, selected: false),
    ]
    #expect(DulcetConnectionsState(ownership: owner, routes: routes, enrolled: true, canUpgrade: true).verificationAddress == nil)
}
