# Apple My Library proof

The app offers Library and Browse only when the connected account advertises `dulcetLibrary` v1.
Library displays the account publication; Browse retains the catalog reader. The core owns
optimistic membership, durable pending writes and their reconnect replay.

## Evidence status

Product source and the native Mac UI target compiled. The scoped Swift presentation tests passed
in the initial implementation. Final full Swift and platform runtime results will be recorded below.
No platform runtime pass or screenshot matrix is claimed by this checkpoint.

## Runtime suite

`apple/DulcetMyLibraryUITestSupport/DulcetMyLibraryUITests.swift` is shared by
`DulcetiOSUITests` (iPhone and iPad), `DulcetTVUITests`, and `DulcetMacUITests`.
It drives the production UI against separate disposable users on a real local Dulcet Server:
Browse album Add, Library membership, durable relaunch, Remove, offline Add, and reconnect replay.
Direct reads bypass each platform's loopback network proxy to distinguish optimistic state from
server state. A separate disposable Navidrome is the positive catalog control for the absent split.
The TV variant also uses the remote's press and hold context menu.

The visual test attaches compressed JPEGs of Library, Browse and an album with Add in light,
dark and the largest Dynamic Type setting. Only app images belong in the committed proof.

## Limits

The unsigned Mac fixture uses the existing DEBUG disk credential store under a per-test UUID.
Its relaunch proves durable My Library and outbox behavior with that account store; it does not
claim signed, entitled Keychain persistence. The test host compiles the production app sources
under a disposable bundle identity. iPhone, iPad and TV use their normal simulator app targets.

The core song facade currently lacks artist IDs and offline playability. Membership actions are
available on unavailable tracks; this shell does not infer offline playback availability.
`FEATURES.yml` remains partial until required CI runs provision and execute this runtime suite.
Local runtime results, when recorded, do not assert that CI has executed the new tests.
