#if os(iOS)
import CarPlay
import DulcetKit
import UIKit
import Observation

/// CarPlay connects to the same production store as the phone scene. No second player or account.
@MainActor
final class DulcetCarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var controller: CPInterfaceController?
    private var tasks: [Task<Void, Never>] = []
    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didConnect interfaceController: CPInterfaceController) {
        controller = interfaceController
        let library = list("Library", symbol: "music.note.list")
        let playlists = list("Playlists", symbol: "music.note.list")
        let recent = list("Recently Played", symbol: "clock")
        let queue = list("Queue", symbol: "list.bullet")
        interfaceController.setRootTemplate(CPTabBarTemplate(templates: [library, playlists, recent, queue]), animated: false) { [weak self] succeeded, _ in
            if !succeeded { self?.controller = nil }
        }
        library.updateSections([CPListSection(items: [
            folder("Albums", query: .albums(.alphabeticalByName)),
            folder("Artists", query: .artists),
            folder("Favourites", query: .favourites),
            action("Now Playing") { [weak self] in self?.showNowPlaying() }
        ])])
        load(.playlists, into: playlists)
        load(.albums(.recent), into: recent)
        refreshQueue(queue)
    }
    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didDisconnect interfaceController: CPInterfaceController) {
        tasks.forEach { $0.cancel() }; tasks = []; controller = nil
    }
    private func list(_ title: String, symbol: String) -> CPListTemplate {
        let list = CPListTemplate(title: title, sections: [])
        list.tabTitle = title; list.tabImage = UIImage(systemName: symbol)
        list.emptyViewTitleVariants = ["No music here yet"]
        list.emptyViewSubtitleVariants = ["Connect your music server in Dulcet on iPhone."]
        return list
    }
    private func action(_ title: String, perform: @escaping @MainActor () -> Void) -> CPListItem {
        let item = CPListItem(text: title, detailText: nil)
        item.handler = { _, completion in perform(); completion() }
        return item
    }
    private func folder(_ title: String, query: DulcetLibraryQuery) -> CPListItem {
        action(title) { [weak self] in
            guard let self else { return }
            let child = self.list(title, symbol: "music.note")
            self.push(child)
            self.load(query, into: child)
        }
    }
    private func load(_ query: DulcetLibraryQuery, into template: CPListTemplate, offset: Int = 0) {
        tasks.append(Task { [weak self] in
            do {
                // Reserve capacity for Play All and Previous/More, including constrained car displays.
                let capacity = max(1, min(100, CPListTemplate.maximumItemCount - 3))
                let publication = try await DulcetSystemActions.shared.window(query, minimumCount: offset + capacity + 1)
                guard !Task.isCancelled else { return }
                var items = publication.items.dropFirst(offset).prefix(capacity).map { row -> CPListItem in
                    let item = CPListItem(text: row.displayTitle, detailText: row.artistName)
                    item.isEnabled = !row.isUnavailableOffline
                    item.handler = { [weak self] _, completion in
                        guard let self else { completion(); return }
                        let childQuery: DulcetLibraryQuery? = switch row.kind {
                        case .album: .album(rawID: row.id.rawID)
                        case .artist: .artist(rawID: row.id.rawID)
                        case .playlist: .playlist(rawID: row.id.rawID)
                        default: nil
                        }
                        if let childQuery {
                            let child = self.list(row.displayTitle, symbol: "music.note")
                            self.push(child)
                            self.load(childQuery, into: child)
                            completion()
                            return
                        }
                        self.tasks.append(Task {
                            do { try await DulcetSystemActions.shared.play(row); self.showNowPlaying() }
                            catch { self.show(error) }
                            completion()
                        })
                    }
                    return item
                }
                if offset == 0, let header = publication.header, [.album, .artist, .playlist].contains(header.kind), let self {
                    items.insert(self.action("Play All") { [weak self] in
                        self?.tasks.append(Task {
                            do { try await DulcetSystemActions.shared.play(header); self?.showNowPlaying() }
                            catch { self?.show(error) }
                        })
                    }, at: 0)
                }
                if let self, offset > 0 {
                    items.append(self.action("Previous") { [weak self, weak template] in
                        guard let template else { return }
                        self?.load(query, into: template, offset: max(0, offset - capacity))
                    })
                }
                if publication.items.count > offset + capacity, let self {
                    items.append(self.action("More…") { [weak self, weak template] in
                        guard let template else { return }
                        self?.load(query, into: template, offset: offset + capacity)
                    })
                }
                template.updateSections([CPListSection(items: items)])
            } catch { template.emptyViewSubtitleVariants = [error.localizedDescription] }
        })
    }
    private func refreshQueue(_ template: CPListTemplate) {
        withObservationTracking {
            let entries = DulcetSystemActions.shared.store?.snapshot.nowPlaying?.queueEntries ?? []
            template.updateSections([CPListSection(items: entries.prefix(CPListTemplate.maximumItemCount).map { entry in
                action(entry.track.title) { [weak self] in
                    DulcetSystemActions.shared.store?.editQueue(.jump(entry.id)); self?.showNowPlaying()
                }
            })])
        } onChange: {
            Task { @MainActor [weak self, weak template] in
                guard let self, let template, self.controller != nil else { return }
                self.refreshQueue(template)
            }
        }
    }
    private func push(_ template: CPTemplate) {
        controller?.pushTemplate(template, animated: true) { [weak self] succeeded, error in
            if !succeeded { self?.show(error ?? DulcetSystemActions.Failure.unavailable) }
        }
    }
    private func showNowPlaying() {
        guard controller?.topTemplate !== CPNowPlayingTemplate.shared else { return }
        push(CPNowPlayingTemplate.shared)
    }
    private func show(_ error: Error) {
        controller?.presentTemplate(CPAlertTemplate(titleVariants: [error.localizedDescription], actions: [CPAlertAction(title: "OK", style: .default) { [weak self] _ in self?.controller?.dismissTemplate(animated: true, completion: { _, _ in }) }]), animated: true, completion: { _, _ in })
    }
}
#endif
