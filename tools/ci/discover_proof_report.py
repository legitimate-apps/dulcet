"""Fail closed on native execution, device identity and causal fixture receipts."""
from dataclasses import dataclass

COMMON = ['testEverywhereQueryPagesAndHeldRouting','testOfflineWishRelaunchRemoveAndScanArrival','testBusyStaleAndNavidromeControl','testAccountSwitchAndIdentityReplacementFence']
def expected_tests(mode):
    if mode == 'tvos':return ['testTVDiscoverShelvesSearchWishScanAndRemoveByRemote', 'testTVDiscoverStatesAndNavidromeByRemote']
    return COMMON + ({'iphone':['testIPhoneLargestTextAndVoiceOverLabels'],
        'ipad':['testIPadSidebarSelectionSurvivesWishlistDetailAndResize'],
        'macos':['testMacKeyboardSearchSidebarAndContextRemove','testMacWindowClosureDropsPendingNavigation']}[mode])
def expected_suite(mode):
    return {'iphone':'DulcetDiscoverWishlistUITests','ipad':'DulcetDiscoverWishlistIPadUITests','macos':'DulcetMacDiscoverWishlistAppTest','tvos':'DulcetTVDiscoverWishlistUITests'}[mode]
def shelf_tests(mode):
    if mode == 'tvos':return expected_tests(mode)
    if mode in {'mac','macos'}:
        return ['testDiscoverShelvesMixedPagingAndShelfWish', 'testDiscoverPublicPlaylistPositionsWishKeyboardAndResize', 'testDiscoverShelvesEmptyFailureCachedVariantsAndNavidrome']
    return ['testDiscoverShelvesEmptyCachedFailureAndNavidrome',
        'testIPadDiscoverShelvesSidebarCompactResizeAndBack' if mode=='ipad' else 'testIPhoneDiscoverShelvesLargestTextPagesAndSources']

def selected_tests(mode, selected=None, shelves=False):
    """Keep the two proof inventories distinct even when they share a test class."""
    if selected and '/' in selected:
        return [selected.rsplit('/', 1)[-1]]
    return shelf_tests(mode) if shelves else expected_tests(mode)

@dataclass(frozen=True)
class Verdict:
    first_cause: str | None = None

def validate_report(report):
    if not report.get('finalized'):return Verdict('result-not-finalized')
    cases=report.get('cases',[])
    expected=report.get('selected') or expected_tests(report['mode'])
    if sorted(c.get('name','') for c in cases)!=sorted(expected) or any(c.get('suite',expected_suite(report['mode']))!=expected_suite(report['mode']) for c in cases):return Verdict('expected-test-not-executed')
    if any(c.get('status')!='passed' for c in cases):return Verdict('test-not-passed')
    if report.get('idiom')!={'iphone':'phone','ipad':'pad','macos':'mac','tvos':'tv'}[report['mode']]:return Verdict('wrong-device-idiom')
    if report['mode'] == 'tvos':
        if sorted(c['name'] for c in cases) != sorted(expected_tests('tvos')):return Verdict('expected-tv-test-not-executed')
        effects=report.get('effects', [])
        for scenario in ['shelves-mixed','shelves-empty','shelves-stale','shelves-unavailable']:
            if not any(e.get('scenario')==scenario and e.get('revision',0)>0 and ('empty' in e or 'stale' in e or e.get('unavailable') is True) for e in effects):return Verdict('shelf-fixture-effect-not-observed')
        if not any(e.get('scenario')=='scan-arrival' and e.get('revision',0)>0 and e.get('completed') is True for e in effects):return Verdict('fixture-effect-not-observed')
        flow=report.get('tvFlow', {})
        if flow != dict(shelfPaging=24,playlistPositions=24,sources=True,searched=True,arrived=True,removed=True):return Verdict('tv-flow-not-observed')
        baseline=report.get('baseline', {})
        if baseline.get('login') is not True or baseline.get('albums',0)<1 or baseline.get('featureCalls')!=0:return Verdict('navidrome-positive-control-missing')
        return Verdict()
    # A focused navigation reproduction is not a complete feature proof.
    if report.get('proofKind')=='shelves':
        if sorted(c.get('name','') for c in cases)!=sorted(shelf_tests(report['mode'])):return Verdict('expected-shelf-test-not-executed')
        for scenario in ['shelves-mixed','shelves-empty','shelves-stale','shelves-unavailable']:
            if not any(e.get('scenario')==scenario and e.get('revision',0)>0 and ('empty' in e or 'stale' in e or e.get('unavailable') is True) for e in report.get('effects',[])):
                return Verdict('shelf-fixture-effect-not-observed')
        return Verdict()
    if report.get('selected'):return Verdict()
    effects=report.get('effects',[])
    for scenario,flag in [('rate-limit','served'),('scan-arrival','completed'),('lost-add-receipt','delivered')]:
        if not any(e.get('scenario')==scenario and e.get('revision',0)>0 and e.get(flag) is True for e in effects):
            return Verdict('fixture-effect-not-observed')
    baseline=report.get('baseline',{})
    if baseline.get('login') is not True or baseline.get('albums',0)<1 or baseline.get('featureCalls')!=0:
        return Verdict('navidrome-positive-control-missing')
    replay=report.get('replay',{})
    if replay.get('removed') is not True or replay.get('applied') is not False:return Verdict('lost-receipt-replay-not-observed')
    return Verdict()

def apple_report(mode,summary,tree,acknowledgements,requests,selected=None):
    cases=[]
    def visit(node):
        if isinstance(node,dict):
            if node.get('nodeType')=='Test Case':cases.append(dict(name=node['name'].removesuffix('()'),suite=node.get('nodeIdentifier','').split('/')[0],status=node.get('result','').lower()))
            for value in node.values():visit(value)
        elif isinstance(node,list):
            for value in node:visit(value)
    visit(tree)
    effects=[];baseline={};replay={};tv_flow={}
    for ack in acknowledgements:
        for effect in ack.get('effects',[]):
            effect=dict(effect)
            if effect.get('scenario')=='rate-limit-120':effect.update(scenario='rate-limit',served=True)
            effects.append(effect)
        if ack.get('scenario')=='tv-flow':tv_flow={k:v for k,v in ack.items() if k!='scenario'}
        if ack.get('scenario')=='navidrome-baseline':baseline=dict(ack.get('baseline',{}))
        if ack.get('scenario')=='lost-add-receipt-replay':replay=ack.get('replay',{})
    nav=[r for r in requests.get('requests',[]) if r.get('channel')=='navidrome']
    if baseline and any(r.get('endpoint')=='getAlbumList2' for r in nav):
        baseline['featureCalls']=sum('Dulcet' in r.get('endpoint','') for r in nav)
    devices=tree.get('devices',[])
    idioms=set()
    for device in devices:
        model=device.get('modelName','')+' '+device.get('deviceName','')
        if device.get('platform')=='macOS':idioms.add('mac')
        elif device.get('platform')=='tvOS' or 'Apple TV' in model:idioms.add('tv')
        elif 'iPad' in model:idioms.add('pad')
        elif 'iPhone' in model:idioms.add('phone')
    return dict(mode=mode,idiom=next(iter(idioms)) if len(idioms)==1 else None,
        finalized=bool(summary.get('finishTime')) and summary.get('totalTestCount')==len(cases),
        cases=cases,effects=effects,baseline=baseline,replay=replay,selected=selected,**({'tvFlow':tv_flow} if mode=='tvos' else {}))

def native_exit_status(code, interrupted, report):
    """Preserve execution failures while handling our post-suite finalization interrupt."""
    if interrupted and validate_report(report).first_cause is None:return 0
    return code
