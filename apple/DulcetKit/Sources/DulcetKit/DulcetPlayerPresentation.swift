#if os(iOS)
import SwiftUI
import UIKit

/// Public sheet and zoom transitions don't expose interactive *presentation* progress from an
/// external mini-player pan. This bridge keeps UIKit's modal lifecycle and native spring
/// animations, and gives the opening and closing pans a single owner on every window size.
@MainActor
final class DulcetPlayerTransition: NSObject, UIViewControllerTransitioningDelegate, UIGestureRecognizerDelegate {
    weak var presenter: UIViewController?
    private var player: UIHostingController<AnyView>?
    private var regions = NSHashTable<DulcetPlayerGestureRegion>.weakObjects()
    private weak var sourceWindow: UIWindow?
    private var sourcePan: UIPanGestureRecognizer?
    private var transition: DulcetPlayerAnimator?
    private var interaction: UIPercentDrivenInteractiveTransition?
    private var requestedPresented = false
    private var lastMotionVelocity: CGFloat = 0
    private var lastMotionTime: TimeInterval = 0
    private var lastMotionDistance: CGFloat = 0
    private var interactionTravel: CGFloat = 1
    private weak var dismissalScrollView: UIScrollView?
    private var scrollWasEnabled = true
    var content: () -> AnyView = { AnyView(EmptyView()) }
    var onPresentedChanged: (Bool) -> Void = { _ in }

    func invalidate() {
        if let sourcePan { sourceWindow?.removeGestureRecognizer(sourcePan) }
        sourcePan = nil
        sourceWindow = nil
        dismissalScrollView?.isScrollEnabled = scrollWasEnabled
        dismissalScrollView = nil
        player?.dismiss(animated: false)
        player = nil
        presenter = nil
        transition = nil
        interaction = nil
        content = { AnyView(EmptyView()) }
        onPresentedChanged = { _ in }
    }

    func register(_ region: DulcetPlayerGestureRegion) {
        regions.add(region)
        guard region.isSource, let window = region.window, sourceWindow !== window else { return }
        if let sourcePan { sourceWindow?.removeGestureRecognizer(sourcePan) }
        let pan = UIPanGestureRecognizer(target: self, action: #selector(openPan(_:)))
        pan.maximumNumberOfTouches = 1
        pan.delegate = self
        window.addGestureRecognizer(pan)
        sourcePan = pan
        sourceWindow = window
    }

    func setPresented(_ presented: Bool) {
        requestedPresented = presented
        if presented, let player { player.rootView = content() }
        if presented, player == nil { open(interactive: false) }
        if !presented, let player, !player.isBeingDismissed, transition == nil {
            beginTransition(presenting: false, interactive: false)
            player.dismiss(animated: true)
        }
    }

    private func open(interactive: Bool) {
        guard player == nil, let presenter, presenter.view.window != nil else { return }
        var owner = presenter
        while let parent = owner.parent { owner = parent }
        guard owner.presentedViewController == nil else { return }
        let host = UIHostingController(rootView: content())
        host.modalPresentationStyle = .custom
        host.transitioningDelegate = self
        host.view.backgroundColor = .clear
        host.view.accessibilityViewIsModal = true
        let pan = UIPanGestureRecognizer(target: self, action: #selector(closePan(_:)))
        pan.maximumNumberOfTouches = 1
        pan.delegate = self
        host.view.addGestureRecognizer(pan)
        player = host
        beginTransition(presenting: true, interactive: interactive)
        requestedPresented = true
        onPresentedChanged(true)
        owner.present(host, animated: true)
    }

    private func beginTransition(presenting: Bool, interactive: Bool) {
        interaction = interactive ? UIPercentDrivenInteractiveTransition() : nil
        interaction?.completionCurve = .easeOut
        let window = sourceWindow ?? player?.view.window ?? presenter?.view.window
        let height = max(1, window?.bounds.height ?? 1)
        let sourceTop = regions.allObjects.first(where: { $0.isSource && $0.window === window && $0.isVisible })
            .map { $0.convert($0.bounds, to: window).minY } ?? height
        // The emerging surface starts at the mini-player, so there is no window-bottom gap
        // before it follows the finger. Dismissal travels fully offscreen.
        interactionTravel = presenting ? min(height, max(1, sourceTop)) : height
        let animator = DulcetPlayerAnimator(presenting: presenting, interactive: interactive, travel: interactionTravel)
        animator.onEnd = { [weak self] finished in
            // Complete UIKit's lifecycle before publishing the SwiftUI presentation state.
            DispatchQueue.main.async {
                guard let self else { return }
                self.transition = nil
                self.interaction = nil
                self.dismissalScrollView?.isScrollEnabled = self.scrollWasEnabled
                self.dismissalScrollView = nil
                if presenting != finished { self.didClose() }
                else if !presenting {
                    self.requestedPresented = true
                    self.onPresentedChanged(true)
                } else if !self.requestedPresented { self.setPresented(false) }
            }
        }
        transition = animator
    }

    private func didClose() {
        dismissalScrollView?.isScrollEnabled = scrollWasEnabled
        dismissalScrollView = nil
        player = nil
        transition = nil
        interaction = nil
        requestedPresented = false
        onPresentedChanged(false)
    }

    @objc private func openPan(_ pan: UIPanGestureRecognizer) {
        if pan.state == .began { open(interactive: true) }
        drive(pan, presenting: true)
    }

    @objc private func closePan(_ pan: UIPanGestureRecognizer) {
        if pan.state == .began, let player, transition == nil {
            if let scroll = dismissalScrollView {
                scrollWasEnabled = scroll.isScrollEnabled
                scroll.isScrollEnabled = false
            }
            beginTransition(presenting: false, interactive: true)
            player.dismiss(animated: true)
        }
        drive(pan, presenting: false)
    }

    private func drive(_ pan: UIPanGestureRecognizer, presenting: Bool) {
        guard let transition, transition.interactive, transition.presenting == presenting else { return }
        let direction: CGFloat = presenting ? -1 : 1
        let distance = pan.translation(in: sourceWindow).y * direction
        var velocity = pan.velocity(in: sourceWindow).y * direction
        let now = ProcessInfo.processInfo.systemUptime
        if pan.state == .began { lastMotionVelocity = 0; lastMotionTime = 0; lastMotionDistance = 0 }
        if pan.state == .changed, abs(distance - lastMotionDistance) > 0.5 {
            lastMotionDistance = distance
            lastMotionVelocity = velocity
            lastMotionTime = now
        }
        // The final touch-up can have zero velocity. Use the recent movement, but never a
        // stale flick when the person has held still before releasing.
        if pan.state == .ended {
            if now - lastMotionTime >= 0.1 { velocity = 0 }
            else if abs(velocity) < 10 { velocity = lastMotionVelocity }
        }
        let travel = interactionTravel
        interaction?.update(min(1, max(0, distance / travel)))
        transition.updateRubber(distance: distance, travel: travel)
        if pan.state == .ended || pan.state == .cancelled || pan.state == .failed {
            transition.returnRubber()
            let finish = pan.state == .ended
                && (distance + velocity * 0.18 > travel * 0.25 || (distance > 40 && velocity > 450))
            DulcetPlayerFrameProbe.event("release presenting=\(presenting) distance=\(distance) velocity=\(velocity) finish=\(finish)")
            // Native percent-driven completion owns both finishing and cancellation. Faster
            // release velocities shorten the remaining animation without a second transition.
            interaction?.completionSpeed = min(2, max(0.8, abs(velocity) / travel))
            if finish { interaction?.finish() } else { interaction?.cancel() }
        }
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard let window = touch.view?.window else { return false }
        let location = touch.location(in: window)
        if gestureRecognizer === sourcePan {
            return player == nil && regions.allObjects.contains {
                $0.isSource && $0.window === window && $0.isVisible
                    && $0.convert($0.bounds, to: window).contains(location)
            }
        }
        dismissalScrollView = nil
        // SwiftUI's custom scrubber isn't a UISlider. Explicit regions protect its entire touch
        // sequence, including a diagonal drag that leaves the region after touch-down.
        if regions.allObjects.contains(where: {
            !$0.isSource && $0.window === window && $0.isVisible && $0.convert($0.bounds, to: window).contains(location)
        }) { return false }
        var view = touch.view
        while let current = view {
            if current is UISlider { return false }
            if let scroll = current as? UIScrollView {
                // The main player's content can scroll at large text sizes. Its downward
                // drag still dismisses at any offset; freeze that scroll during the pan so
                // the content and modal cannot move together. Lyrics/queue are excluded above.
                dismissalScrollView = scroll
            }
            view = current.superview
        }
        return true
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let pan = gestureRecognizer as? UIPanGestureRecognizer else { return false }
        let velocity = pan.velocity(in: pan.view)
        guard transition == nil else { return false }
        let direction: CGFloat = gestureRecognizer === sourcePan ? -1 : 1
        return velocity.y * direction > 0 && abs(velocity.y) > abs(velocity.x)
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        // The cover's horizontal next/previous gesture and fitted player ScrollView keep their
        // own recognition. Direction arbitration above ensures they cannot start dismissal.
        true
    }

    func presentationController(forPresented presented: UIViewController, presenting: UIViewController?, source: UIViewController)
        -> UIPresentationController? {
        DulcetPlayerWindowPresentation(presentedViewController: presented, presenting: presenting)
    }

    func animationController(forPresented presented: UIViewController, presenting: UIViewController,
                             source: UIViewController) -> (any UIViewControllerAnimatedTransitioning)? { transition }
    func animationController(forDismissed dismissed: UIViewController) -> (any UIViewControllerAnimatedTransitioning)? { transition }
    func interactionControllerForPresentation(using animator: any UIViewControllerAnimatedTransitioning)
        -> (any UIViewControllerInteractiveTransitioning)? { interaction }
    func interactionControllerForDismissal(using animator: any UIViewControllerAnimatedTransitioning)
        -> (any UIViewControllerInteractiveTransitioning)? { interaction }
}

@MainActor
private final class DulcetPlayerWindowPresentation: UIPresentationController {
    override var frameOfPresentedViewInContainerView: CGRect { containerView?.bounds ?? .zero }
    override func containerViewWillLayoutSubviews() {
        super.containerViewWillLayoutSubviews()
        // Keep the same hosting controller when rotating or resizing the window.
        guard presentedView?.transform.isIdentity == true else { return }
        presentedView?.bounds.size = frameOfPresentedViewInContainerView.size
        presentedView?.center = CGPoint(x: frameOfPresentedViewInContainerView.midX,
                                       y: frameOfPresentedViewInContainerView.midY)
    }
}

/// UIKit owns the transition context and accessibility presentation. Finger updates are applied
/// by UIKit's percent-driven controller; release velocity selects completion and its speed.
@MainActor
private final class DulcetPlayerAnimator: NSObject, UIViewControllerAnimatedTransitioning {
    let presenting: Bool
    let interactive: Bool
    private var movingView: UIView?
    private var travel: CGFloat = 1
    var onEnd: (Bool) -> Void = { _ in }
    private let reduceMotion = UIAccessibility.isReduceMotionEnabled

    init(presenting: Bool, interactive: Bool, travel: CGFloat) {
        self.presenting = presenting
        self.interactive = interactive
        self.travel = travel
    }

    func transitionDuration(using context: (any UIViewControllerContextTransitioning)?) -> TimeInterval {
        reduceMotion ? 0.2 : 0.42
    }

    func animateTransition(using context: any UIViewControllerContextTransitioning) {
        let key: UITransitionContextViewControllerKey = presenting ? .to : .from
        guard let controller = context.viewController(forKey: key) else { preconditionFailure("Player transition missing controller") }
        let view = context.view(forKey: presenting ? .to : .from) ?? controller.view!
        movingView = view
        view.layer.cornerCurve = .continuous
        view.layer.masksToBounds = true
        view.layer.cornerRadius = presenting && !reduceMotion ? 24 : 0
        if presenting { view.frame = context.finalFrame(for: controller) }
        travel = max(1, min(travel, context.containerView.bounds.height))
        if presenting { context.containerView.addSubview(view) }
        // Reduce Motion removes the spatial animation, while keeping the gesture's completion
        // rules. UIKit's native modal accessibility and focus restoration still apply.
        let offscreen = CGAffineTransform(translationX: 0, y: travel)
        if presenting {
            view.transform = reduceMotion ? .identity : offscreen
            view.alpha = reduceMotion ? 0 : 1
        }
        let animations = {
            view.transform = self.reduceMotion ? .identity : (self.presenting ? .identity : offscreen)
            view.alpha = self.reduceMotion && !self.presenting ? 0 : 1
            view.layer.cornerRadius = self.presenting || self.reduceMotion ? 0 : 24
        }
        let completion: (Bool) -> Void = { [weak self] _ in
            guard let self else { return }
            let finished = !context.transitionWasCancelled
            view.transform = .identity
            view.layer.cornerRadius = 0
            view.layer.sublayerTransform = CATransform3DIdentity
            view.alpha = 1
            if (self.presenting && !finished) || (!self.presenting && finished) { view.removeFromSuperview() }
            context.completeTransition(finished)
            if self.presenting == finished {
                // A canceled dismissal restores UIKit's saved geometry after completion.
                // Establish the visible resting frame then, rather than changing that saved
                // frame while the percent-driven transition is rewinding.
                view.transform = .identity
                view.frame = context.containerView.bounds
                view.setNeedsLayout()
            }
            self.onEnd(finished)
        }
        if interactive {
            UIView.animate(withDuration: transitionDuration(using: context), delay: 0,
                           options: [.curveLinear, .allowUserInteraction], animations: animations, completion: completion)
        } else {
            UIView.animate(withDuration: transitionDuration(using: context), delay: 0,
                           usingSpringWithDamping: 1, initialSpringVelocity: 0,
                           options: [.allowUserInteraction], animations: animations, completion: completion)
        }
    }

    func updateRubber(distance: CGFloat, travel: CGFloat) {
        let excess = distance < 0 ? distance : max(0, distance - travel)
        let rubber = excess / (1 + abs(excess) / 80)
        if !reduceMotion {
            movingView?.layer.sublayerTransform = CATransform3DMakeTranslation(0, (presenting ? -1 : 1) * rubber, 0)
        }
    }

    func returnRubber() {
        guard let view = movingView,
              !CATransform3DEqualToTransform(view.layer.sublayerTransform, CATransform3DIdentity) else { return }
        UIView.animate(withDuration: 0.2, delay: 0, options: [.beginFromCurrentState, .curveEaseOut]) {
            view.layer.sublayerTransform = CATransform3DIdentity
        }
    }

}

private struct DulcetPlayerTransitionKey: EnvironmentKey {
    static let defaultValue: DulcetPlayerTransition? = nil
}

extension EnvironmentValues {
    var dulcetPlayerTransition: DulcetPlayerTransition? {
        get { self[DulcetPlayerTransitionKey.self] }
        set { self[DulcetPlayerTransitionKey.self] = newValue }
    }
}

/// A noninteractive geometry marker: the pan recognizer lives on the window/presented view,
/// and consults these current UIKit bounds at touch-down, without a SwiftUI state update per frame.
@MainActor
final class DulcetPlayerGestureRegion: UIView {
    var transition: DulcetPlayerTransition?
    var isSource = false
    var isVisible: Bool {
        guard bounds.width > 0, bounds.height > 0 else { return false }
        var ancestor: UIView? = self
        while let view = ancestor {
            if view.isHidden || view.alpha < 0.01 { return false }
            ancestor = view.superview
        }
        return window != nil
    }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { transition?.register(self) }
    }
}

private struct DulcetPlayerRegion: UIViewRepresentable {
    let transition: DulcetPlayerTransition?
    let isSource: Bool
    func makeUIView(context: Context) -> DulcetPlayerGestureRegion {
        let view = DulcetPlayerGestureRegion()
        view.isUserInteractionEnabled = false
        view.isSource = isSource
        view.transition = transition
        return view
    }
    func updateUIView(_ view: DulcetPlayerGestureRegion, context: Context) {
        view.transition = transition
        transition?.register(view)
    }
}

extension View {
    func dulcetPlayerGestureRegion(source: Bool = false) -> some View {
        modifier(DulcetPlayerRegionModifier(source: source))
    }
}

private struct DulcetPlayerRegionModifier: ViewModifier {
    @Environment(\.dulcetPlayerTransition) private var transition
    let source: Bool
    func body(content: Content) -> some View {
        content.background(DulcetPlayerRegion(transition: transition, isSource: source))
    }
}

struct DulcetPlayerPresenter: UIViewControllerRepresentable {
    let transition: DulcetPlayerTransition
    let presented: Bool
    let content: () -> AnyView
    let onPresentedChanged: (Bool) -> Void
    func makeCoordinator() -> DulcetPlayerTransition { transition }
    static func dismantleUIViewController(_ controller: UIViewController, coordinator: DulcetPlayerTransition) {
        coordinator.invalidate()
    }
    func makeUIViewController(context: Context) -> UIViewController {
        let controller = UIViewController()
        controller.view.isUserInteractionEnabled = false
        transition.presenter = controller
        return controller
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {
        transition.presenter = controller
        transition.content = content
        transition.onPresentedChanged = onPresentedChanged
        transition.setPresented(presented)
    }
}
#endif
