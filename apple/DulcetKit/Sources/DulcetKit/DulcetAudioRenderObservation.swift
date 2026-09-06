#if DEBUG
import AVFoundation
import Foundation
import MediaToolbox

/// Test-only measurement at AVFoundation's post-effects processing callback, before the OS HAL.
/// Source buffers, frame counts and flags are returned unchanged. This does not observe speakers.
final class DulcetAudioRenderObservation: @unchecked Sendable {
    enum Failure: Error {
        case noCurrentItem, noAudioTrack
        case tapCreation(OSStatus)
    }

    struct Snapshot: Sendable {
        var peakAmplitude: Float = 0
        var framesSeen = 0
        var samplesSeen = 0
        var bufferCount = 0
        var prepareCount = 0
        var sourceErrors = 0
        var unsupportedBuffers = 0
        var nonFiniteSamples = 0
    }

    private let lock = NSLock()
    private var measurement = Snapshot()
    // Accessed only between the tap's paired prepare/unprepare callbacks.
    private var format = AudioStreamBasicDescription()

    var snapshot: Snapshot {
        lock.lock()
        defer { lock.unlock() }
        return measurement
    }

    func makeAudioMix(track: AVAssetTrack) throws -> AVAudioMix {
        let storage = Unmanaged.passRetained(self).toOpaque()
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: storage,
            init: { _, info, out in out.pointee = info },
            finalize: { tap in
                Unmanaged<DulcetAudioRenderObservation>.fromOpaque(
                    MTAudioProcessingTapGetStorage(tap)
                ).release()
            },
            prepare: { tap, _, format in
                let observer = DulcetAudioRenderObservation.observer(tap)
                observer.format = format.pointee
                observer.lock.lock()
                observer.measurement.prepareCount += 1
                observer.lock.unlock()
            },
            unprepare: { _ in },
            process: { tap, requestedFrames, _, buffers, framesOut, flagsOut in
                framesOut.pointee = 0
                let status = MTAudioProcessingTapGetSourceAudio(
                    tap, requestedFrames, buffers, flagsOut, nil, framesOut
                )
                DulcetAudioRenderObservation.observer(tap).record(
                    buffers: buffers, frames: framesOut.pointee, status: status
                )
            }
        )
        var tap: MTAudioProcessingTap?
        let status = MTAudioProcessingTapCreate(
            kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap
        )
        guard status == noErr, let tap else {
            Unmanaged<DulcetAudioRenderObservation>.fromOpaque(storage).release()
            throw Failure.tapCreation(status)
        }
        let parameters = AVMutableAudioMixInputParameters(track: track)
        parameters.audioTapProcessor = tap
        let mix = AVMutableAudioMix()
        mix.inputParameters = [parameters]
        return mix
    }

    private static func observer(_ tap: MTAudioProcessingTap) -> DulcetAudioRenderObservation {
        Unmanaged<DulcetAudioRenderObservation>.fromOpaque(
            MTAudioProcessingTapGetStorage(tap)
        ).takeUnretainedValue()
    }

    private func record(buffers: UnsafeMutablePointer<AudioBufferList>, frames: Int, status: OSStatus) {
        var peak: Float = 0
        var invalid = 0
        var samplesSeen = 0
        let supported = format.mFormatID == kAudioFormatLinearPCM
            && format.mFormatFlags & kAudioFormatFlagIsFloat != 0
            && format.mFormatFlags & kAudioFormatFlagIsBigEndian == 0
            && format.mBitsPerChannel == 32
        if status == noErr, frames > 0, supported {
            for buffer in UnsafeMutableAudioBufferListPointer(buffers) {
                guard let data = buffer.mData else { invalid += 1; continue }
                let count = min(Int(buffer.mDataByteSize) / MemoryLayout<Float>.size,
                                frames * Int(buffer.mNumberChannels))
                samplesSeen += count
                let samples = data.assumingMemoryBound(to: Float.self)
                for index in 0..<count {
                    let sample = samples[index]
                    if sample.isFinite { peak = max(peak, abs(sample)) }
                    else { invalid += 1 }
                }
            }
        }
        lock.lock()
        if status != noErr { measurement.sourceErrors += 1 }
        else if frames > 0 {
            measurement.bufferCount += 1
            measurement.framesSeen += frames
            measurement.samplesSeen += samplesSeen
            if !supported { measurement.unsupportedBuffers += 1 }
            measurement.nonFiniteSamples += invalid
            measurement.peakAmplitude = max(measurement.peakAmplitude, peak)
        }
        lock.unlock()
    }
}
#endif
