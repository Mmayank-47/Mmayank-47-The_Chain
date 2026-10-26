# ResQMesh contacts + distress window + mesh ACK

Approved 2026-10-26.

## Contacts
- After handshake, exchange `m9.card` with `{id, name, occ}` only (Priority.CHATTER).
- Mesh tab: **Saved contacts** section above peers; Save stores card in app-private prefs.
- Online = nodeId currently in verified Nearby peers; else Offline.
- Blood / phone / allergies / conditions / biometric never leave the phone.

## Distress 2-minute window
- On help send, origin keeps an active distress until `now + 120s`.
- Each tick while active, re-originate case (+ pin if any) with a fresh HLC so new joiners receive it; phones that already have the claim id ignore duplicates.
- First Accept anywhere originates `m9.ack` `{c, by, name?}` (DISTRESS), clears active distress mesh-wide, marks claim accepted with responder id/name.

## Kinds
Add `m9.card` and `m9.ack` to Kotlin + Python `KINDS` / `KIND_PRIORITY` (set equality).
