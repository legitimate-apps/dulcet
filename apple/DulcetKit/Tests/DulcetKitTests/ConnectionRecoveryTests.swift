import Testing
@testable import DulcetKit

@Test func httpAdoptedAccountVerifiesThroughItsAdmittedHTTPSInsteadOfItsFrozenAnchor() {
    let owner = DulcetConnectionOwnership(accountID: "fixture", anchorURL: "http://legacy.example.test/music",
        username: "listener", serverIdentity: "lineage")
    let home = DulcetConnectionRow(id: "home", address: "https://home.example.test/music", label: nil,
        verifiedAtEnrollment: true, removed: false, selected: true)
    let remote = DulcetConnectionRow(id: "remote", address: "https://remote.example.test/music", label: nil,
        verifiedAtEnrollment: true, removed: false, selected: false)
    let adopted = DulcetConnectionsState(ownership: owner, routes: [remote, home], enrolled: true, canUpgrade: true)
    #expect(adopted.verificationAddress == home.address)
    #expect(adopted.ownership == owner)
    let removedHome = DulcetConnectionRow(id: home.id, address: home.address, label: nil,
        verifiedAtEnrollment: false, removed: true, selected: false)
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
        DulcetConnectionRow(id: "bad", address: "https://unverified.example.test", label: nil, verifiedAtEnrollment: false, removed: false, selected: true),
        DulcetConnectionRow(id: "removed", address: "https://removed.example.test", label: nil, verifiedAtEnrollment: true, removed: true, selected: false),
        DulcetConnectionRow(id: "http", address: owner.anchorURL, label: nil, verifiedAtEnrollment: true, removed: false, selected: false),
    ]
    #expect(DulcetConnectionsState(ownership: owner, routes: routes, enrolled: true, canUpgrade: true).verificationAddress == nil)
}


@Test func actualConnectionCheckOverridesHistoricEnrollmentWithoutChangingItsAddress() {
    let unavailable = DulcetConnectionRow(id: "home", address: "https://home.example.test/music", label: "Home",
        verifiedAtEnrollment: true, removed: false, selected: false, checkResult: "Unavailable")
    let foreign = DulcetConnectionRow(id: "remote", address: "https://remote.example.test/music", label: "Remote",
        verifiedAtEnrollment: true, removed: false, selected: false, checkResult: "DifferentServer")
    let ready = DulcetConnectionRow(id: "tunnel", address: "https://tunnel.example.test/music", label: "Tunnel",
        verifiedAtEnrollment: true, removed: false, selected: true, checkResult: "Ready")
    #expect(unavailable.statusText == DulcetStrings.connectionUnavailable)
    #expect(foreign.statusText == DulcetStrings.connectionDifferentServer)
    #expect(ready.statusText == DulcetStrings.connectionCurrentHTTPS)
    #expect(unavailable.address == "https://home.example.test/music")
    #expect(unavailable.checkedAt == nil, "An unrecorded check cannot invent a wall time")
}

@Test func runtimeAdmissionCanVerifyThroughAnInitiallyUnreachableDirectoryRouteWithoutInventingEnrollmentHistory() {
    let owner = DulcetConnectionOwnership(accountID: "fixture", anchorURL: "http://legacy.example.test/music",
        username: "listener", serverIdentity: "lineage")
    let admitted = DulcetConnectionRow(id: "private", address: "https://private.example.test/music", label: nil,
        verifiedAtEnrollment: false, removed: false, selected: true, checkResult: "Ready")
    let state = DulcetConnectionsState(ownership: owner, routes: [admitted], enrolled: true, canUpgrade: true)
    #expect(state.verificationAddress == admitted.address)
    #expect(admitted.statusText == DulcetStrings.connectionCurrentHTTPS)
    #expect(!admitted.verifiedAtEnrollment)
    #expect(admitted.checkedAt == nil, "Runtime selection cannot invent an enrollment timestamp")
    let observed = DulcetConnectionRow(id: "private", address: admitted.address, label: nil,
        verifiedAtEnrollment: false, removed: false, selected: false, checkResult: "Ready")
    #expect(observed.statusText == DulcetStrings.connectionVerifiedHTTPS)
    #expect(state.ownership == owner)
}
