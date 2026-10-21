import Foundation
import Testing
@testable import DulcetKit

@Suite
struct OfflineLibraryResourceTests {
    @Test func validatedRangeTeeUsesOneRequestAndReturnsSameBytesAndOperation() {
        let bytes = Data("ID3validated-audio".utf8)
        let source = OfflineResourceStub(outcome: .loaded(data: bytes, contentInformation:
            .init(contentLength: 7_000, supportsByteRanges: true, container: .mp3)))
        let observed = OfflineObservations()
        let tee = DulcetCachingPlaybackResource(source: source) { request, data, information in
            observed.record(request: request, data: data, length: information.contentLength)
        }
        let request = DulcetPlaybackResourceLoadRequest(range: .init(start: 256, endInclusive: 512), requiresAudioSignature: false)
        let operation = tee.load(request) { outcome in
            if case let .loaded(data, information) = outcome {
                #expect(data == bytes)
                #expect(information.contentLength == 7_000)
            } else { Issue.record("tee changed successful playback result") }
        }
        #expect(source.requests == [request])
        #expect(observed.count == 1)
        #expect(observed.bytes == bytes)
        #expect(observed.offset == 256)
        #expect(operation === source.operation)
        operation.cancel()
        #expect(source.operation.cancelled)
    }

    @Test func rejectedAndCancelledResponsesNeverBecomeCacheBytes() {
        for result in [DulcetPlaybackResourceLoadOutcome.failed(error: .unexpectedBinary, refreshReason: .validationFailed), .cancelled] {
            let source = OfflineResourceStub(outcome: result)
            let observed = OfflineObservations()
            let tee = DulcetCachingPlaybackResource(source: source) { request, data, information in
                observed.record(request: request, data: data, length: information.contentLength)
            }
            _ = tee.load(.init(range: .init(start: 0, endInclusive: 99), requiresAudioSignature: true)) { outcome in
                switch outcome {
                case .failed(error: .unexpectedBinary, refreshReason: .validationFailed), .cancelled: break
                default: Issue.record("cache wrapper changed failure")
                }
            }
            #expect(observed.count == 0)
            #expect(source.requests.count == 1)
        }
    }
}

private final class OfflineResourceStub: DulcetPlaybackResourceLoading, @unchecked Sendable {
    let outcome: DulcetPlaybackResourceLoadOutcome
    let operation = OfflineResourceOperation()
    var requests: [DulcetPlaybackResourceLoadRequest] = []
    init(outcome: DulcetPlaybackResourceLoadOutcome) { self.outcome = outcome }
    var description: String { "test resource" }
    func load(_ request: DulcetPlaybackResourceLoadRequest,
              completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void) -> any DulcetPlaybackResourceLoadOperation {
        requests.append(request)
        completion(outcome)
        return operation
    }
}
private final class OfflineResourceOperation: DulcetPlaybackResourceLoadOperation, @unchecked Sendable {
    var cancelled = false
    func cancel() { cancelled = true }
}
private final class OfflineObservations: @unchecked Sendable {
    var count = 0
    var bytes = Data()
    var offset: Int64 = -1
    func record(request: DulcetPlaybackResourceLoadRequest, data: Data, length: Int64) {
        count += 1; bytes = data; offset = request.range.start
    }
}
