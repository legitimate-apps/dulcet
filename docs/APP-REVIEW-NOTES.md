# App Review notes

## What Dulcet is

Dulcet is a native music player for libraries stored on an OpenSubsonic-compatible server. An
OpenSubsonic server is software a user operates or chooses that exposes a documented REST protocol for
browsing a music library and requesting audio. Dulcet provides its own native interface, local data
model, cache, playback behavior, queue, and offline behavior.

## Guideline 4.2.7

Guideline 4.2.7 does not apply. Dulcet is not a remote desktop application: it does not mirror a host
screen, render software executing on another device, or stream a remote user interface. It exchanges
structured music-library data and audio through an openly documented REST protocol, in the same
client/server pattern used by native mail and feed clients.

## Guideline 4.2.3(i)

The review build will include access to a permanent public demonstration server so reviewers do not
need to install or configure server software. The account will expose a small, legally clean synthetic
music corpus and exercise the same documented protocol as any user-provided server.

## Review access

The permanent review service is a Phase 6 distribution prerequisite and is not provisioned in Phase
0. Before the first submission, replace this status with the non-expiring demonstration URL, username,
and password in App Store Connect's private Review Information fields. Credentials must not be
committed to this public repository. The service must remain available for every initial and update
review.
