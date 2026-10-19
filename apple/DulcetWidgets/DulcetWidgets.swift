import AppIntents
import DulcetKit
import SwiftUI
import WidgetKit

struct DulcetEntry: TimelineEntry { let date: Date; let snapshot: DulcetSurfaceSnapshot }
struct DulcetProvider: TimelineProvider {
    func placeholder(in context: Context) -> DulcetEntry { .init(date: .now, snapshot: .init(title: "Your music", subtitle: "Dulcet")) }
    func getSnapshot(in context: Context, completion: @escaping (DulcetEntry) -> Void) { completion(entry()) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<DulcetEntry>) -> Void) {
        completion(Timeline(entries: [entry()], policy: .after(.now.addingTimeInterval(900))))
    }
    private func entry() -> DulcetEntry {
        let group = DulcetSurfaceSnapshot.groupID(bundleID: Bundle.main.bundleIdentifier ?? "")
        return .init(date: .now, snapshot: .read(from: UserDefaults(suiteName: group)))
    }
}
struct DulcetWidgetView: View {
    let entry: DulcetEntry
    var recent = false
    var pinned = false
    @Environment(\.widgetFamily) private var family
    private var isCircular: Bool {
        #if os(iOS)
        family == .accessoryCircular
        #else
        false
        #endif
    }
    private var isRectangular: Bool {
        #if os(iOS)
        family == .accessoryRectangular
        #else
        false
        #endif
    }
    var body: some View {
        Group {
            if isCircular {
                Button(intent: DulcetTogglePlaybackIntent()) {
                    Image(systemName: entry.snapshot.isPlaying ? "pause.fill" : "play.fill")
                }.accessibilityLabel((entry.snapshot.isPlaying ? "Pause " : "Resume ") + entry.snapshot.title)
            } else if isRectangular {
                HStack {
                    VStack(alignment: .leading) {
                        Text(entry.snapshot.title).font(.headline).lineLimit(1)
                        Text(entry.snapshot.subtitle).font(.caption).lineLimit(1)
                    }
                    Button(intent: DulcetTogglePlaybackIntent()) {
                        Image(systemName: entry.snapshot.isPlaying ? "pause.fill" : "play.fill")
                    }.accessibilityLabel(entry.snapshot.isPlaying ? "Pause Dulcet" : "Resume Dulcet")
                }
            } else {
            VStack(alignment: .leading, spacing: 8) {
                if pinned {
                    Label("Pinned", systemImage: "pin").font(.headline)
                    ForEach((entry.snapshot.pinned ?? []).prefix(3)) { item in
                        if let provider = item.providerID, let rawID = item.rawID, let kind = item.kind {
                            Button(intent: DulcetPlayPinnedIntent(provider: provider, itemID: rawID, kind: kind)) {
                                Text(item.title).font(.caption).lineLimit(1)
                            }.buttonStyle(.plain).accessibilityLabel("Play " + item.title)
                        }
                    }
                    if entry.snapshot.pinned?.isEmpty != false { Text("Pin music in your Dulcet library to see it here.").font(.caption) }
                } else if recent {
                    Label("Recently Played", systemImage: "clock") .font(.headline)
                    ForEach(entry.snapshot.recent.prefix(3)) { item in
                        if let provider = item.providerID, let rawID = item.rawID {
                            Button(intent: DulcetPlayRecentIntent(provider: provider, songID: rawID, name: item.title)) {
                                Text(item.title).font(.caption).lineLimit(1)
                            }.buttonStyle(.plain).accessibilityLabel("Play " + item.title)
                        } else { Text(item.title).font(.caption).lineLimit(1) }
                    }
                    if entry.snapshot.recent.isEmpty { Text("Play music in Dulcet to see it here.").font(.caption) }
                } else {
                    Label(entry.snapshot.title, systemImage: "music.note").font(.headline).lineLimit(2)
                    Text(entry.snapshot.subtitle).font(.caption).lineLimit(1)
                    Button(intent: DulcetTogglePlaybackIntent()) {
                        Label(entry.snapshot.isPlaying ? "Pause" : "Play", systemImage: entry.snapshot.isPlaying ? "pause.fill" : "play.fill")
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(entry.snapshot.isPlaying ? "Pause Dulcet" : "Resume Dulcet")
                }
            }
            }
        }
        .containerBackground(.fill.tertiary, for: .widget)
    }
}
struct DulcetNowPlayingWidget: Widget {
    let kind = "DulcetNowPlaying"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: DulcetProvider()) { DulcetWidgetView(entry: $0) }
            .configurationDisplayName("Dulcet Now Playing")
            .description("See your current song and play or pause your queue.")
            .supportedFamilies(families)
    }
    private var families: [WidgetFamily] {
        #if os(iOS)
        [.systemSmall, .systemMedium, .accessoryRectangular, .accessoryCircular]
        #else
        [.systemSmall, .systemMedium]
        #endif
    }
}
struct DulcetRecentWidget: Widget {
    let kind = "DulcetRecent"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: DulcetProvider()) { DulcetWidgetView(entry: $0, recent: true) }
            .configurationDisplayName("Dulcet Recently Played")
            .description("Your most recently played songs on this device.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}
struct DulcetPinnedWidget: Widget {
    let kind = "DulcetPinned"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: DulcetProvider()) { DulcetWidgetView(entry: $0, pinned: true) }
            .configurationDisplayName("Dulcet Pinned")
            .description("Music pinned in this account on this device.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}
#if os(iOS)
@available(iOS 18.0, *)
struct DulcetPlaybackControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "DulcetPlaybackControl") {
            ControlWidgetButton(action: DulcetTogglePlaybackIntent()) { Label("Play / Pause", systemImage: "playpause.fill") }
        }
        .displayName("Dulcet Play / Pause")
        .description("Play or pause your Dulcet queue.")
    }
}
#endif
@main
struct DulcetWidgets: WidgetBundle {
    var body: some Widget {
        DulcetNowPlayingWidget()
        DulcetRecentWidget()
        DulcetPinnedWidget()
        #if os(iOS)
        if #available(iOS 18.0, *) { DulcetPlaybackControl() }
        #endif
    }
}

#Preview("Now Playing", as: .systemSmall) {
    DulcetNowPlayingWidget()
} timeline: {
    DulcetEntry(date: .now, snapshot: .init(title: "UI Playback Canary", subtitle: "Threshold Boundary", isPlaying: true))
}

#Preview("Recently Played", as: .systemMedium) {
    DulcetRecentWidget()
} timeline: {
    DulcetEntry(date: .now, snapshot: .init(title: "UI Playback Canary", subtitle: "Threshold Boundary"))
}

#if os(iOS)
#Preview("Lock Screen", as: .accessoryRectangular) {
    DulcetNowPlayingWidget()
} timeline: {
    DulcetEntry(date: .now, snapshot: .init(title: "UI Playback Canary", subtitle: "Threshold Boundary", isPlaying: true))
}
#endif
