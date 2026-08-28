import Foundation
import Testing
@testable import DulcetKit

@Test
func playbackPlanAndOpaqueIdentifiersDoNotRenderTheirValues() {
    let resourceCanary = "resource-url-query-canary"
    let resource = ContractTestPlaybackResource(canary: resourceCanary)
    let sessionCanary = "session-opaque-canary"
    let attemptCanary = "attempt-opaque-canary"
    let plan = DulcetPlaybackPlan(
        playbackSessionID: DulcetPlaybackSessionID(sessionCanary),
        attemptID: DulcetPlaybackAttemptID(attemptCanary),
        deliveryProtocol: .httpProgressive,
        expectedContainer: .mp3,
        resource: resource,
        metadata: DulcetNowPlayingMetadata(title: "Fixture")
    )
    var dumped = ""
    dump(plan, to: &dumped)
    let rendered = [
        String(describing: plan),
        String(reflecting: plan),
        dumped,
        plan.playbackSessionID.description,
        plan.attemptID.description,
    ]

    for canary in [sessionCanary, attemptCanary, resourceCanary] {
        #expect(rendered.allSatisfy { !$0.contains(canary) })
    }
    #expect(rendered.allSatisfy { $0.contains("<redacted>") || $0.contains("<opaque>") })
}

@Test
func everyPlaybackCommandPreservesItsCorrelationIdentity() {
    let commandID = DulcetPlaybackCommandID("command:opaque")
    let plan = contractTestPlan()
    let commands: [DulcetPlaybackCommand] = [
        .prepare(commandID: commandID, plan: plan),
        .play(commandID: commandID),
        .pause(commandID: commandID),
        .stop(commandID: commandID),
        .seek(commandID: commandID, position: 12),
        .setVolume(commandID: commandID, volume: 0.5),
        .setRate(commandID: commandID, rate: 1),
        .replaceCurrent(commandID: commandID, plan: plan),
        .preloadNext(commandID: commandID, plan: plan),
        .release(commandID: commandID),
    ]

    #expect(commands.count == 10)
    #expect(commands.allSatisfy { $0.commandID == commandID })
}

@Test
func replacementAndPreloadBoundaryEventsRemainCorrelatedToTheOutgoingAttempt() {
    let oldAttempt = DulcetPlaybackAttemptID("attempt:old")
    let newAttempt = DulcetPlaybackAttemptID("attempt:new")

    #expect(
        DulcetPlaybackEvent.attemptReplaced(
            oldAttemptID: oldAttempt,
            newAttemptID: newAttempt
        ).attemptID == oldAttempt
    )
    #expect(
        DulcetPlaybackEvent.advancedToPreloaded(
            oldAttemptID: oldAttempt,
            newAttemptID: newAttempt
        ).attemptID == oldAttempt
    )
}

private final class ContractTestPlaybackResource: DulcetPlaybackResource, @unchecked Sendable {
    private let canary: String

    init(canary: String = "resource-canary") {
        self.canary = canary
    }

    var description: String { "ContractTestPlaybackResource(<redacted>)" }
}

private func contractTestPlan() -> DulcetPlaybackPlan {
    DulcetPlaybackPlan(
        playbackSessionID: DulcetPlaybackSessionID("session:fixture"),
        attemptID: DulcetPlaybackAttemptID("attempt:fixture"),
        deliveryProtocol: .httpProgressive,
        expectedContainer: .mp3,
        resource: ContractTestPlaybackResource(),
        metadata: DulcetNowPlayingMetadata(title: "Fixture")
    )
}
