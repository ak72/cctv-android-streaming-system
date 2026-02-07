🔥 Principal Review of Your CRITICAL #4 - Connection state authority Plan

Now I'll go deeper than validation - I'll harden it.

✅ What You Got Exactly Right
✔ 1. Explicit STREAM_STATE message

This is the correct fix, not a workaround.

Never let clients infer streaming state from side effects like:

receiving CSD

socket open

decoder running

keyframe arrival

Those are transport signals, not system state.

Your approach converts implicit behavior → explicit protocol.

That is senior-level protocol design.

✔ 2. Viewer fallback for old Primaries

This is extremely important - many engineers forget protocol evolution.

Your timeout fallback:

"keep existing inference as fallback when STREAM_STATE is not received"

✅ Perfect.

You just made your protocol:

version tolerant

deployable gradually

production safe

Excellent thinking.

✔ 3. Documentation update

Do not underestimate this.

Your repo already emphasizes protocol clarity (great sign): README.md (architecture), FRAMED_PROTOCOL.md (binary framing), and state/protocol notes in STATE_MACHINE.md. Maintaining these docs is what separates hobby projects from real systems.

⚠️ Now - The Important Improvements (Read Carefully)

Your plan is 90% excellent.

Let's fix the remaining 10% that prevents future pain.

⭐ CRITICAL Improvement #1 - DO NOT use strings for state

You proposed:

STREAM_STATE|ACTIVE
STREAM_STATE|RECONFIGURING

This is fine short-term.

But long-term?

Strings rot.

Instead - define a protocol enum.

Example:

STREAM_STATE|1 // ACTIVE
STREAM_STATE|2 // RECONFIGURING
STREAM_STATE|3 // PAUSED

Then document:

1 = ACTIVE
2 = RECONFIGURING
3 = PAUSED
Why this matters:

Strings create:

typos

case bugs

parsing overhead

harder protocol evolution

Enums create:

stability

faster parsing

easier versioning

smaller packets

This is how mature streaming protocols behave.

⭐ CRITICAL Improvement #2 - Send FULL state snapshot after handshake

Right now your plan sends state around STREAM_ACCEPTED.

Good - but not sufficient.

Always send a state snapshot after AUTH / SESSION.

Example:

AUTH_OK
SESSION|id=...
STREAM_STATE|RECONFIGURING

Why?

Because TCP reconnects happen.

Viewer must NEVER guess the server state after reconnect.

Think:

👉 Late joiner problem

If viewer connects mid-reconfiguration - what happens?

Without snapshot → race condition.

With snapshot → deterministic.

⭐ CRITICAL Improvement #3 - Separate STREAM_STATUS vs STREAM_EVENT

This is a Principal-level refinement.

Do NOT mix:

STATUS → current truth
EVENT → something that just happened

Example:

GOOD:

STREAM_STATUS|ACTIVE
STREAM_EVENT|RECONFIG_STARTED
STREAM_EVENT|RECONFIG_DONE

Why?

Because:

Events are lossy.
Status must be authoritative.

If a packet drops or reconnect occurs:

Events vanish.
Status survives.

Most engineers learn this after painful production bugs 🙂

⭐ CRITICAL Improvement #4 - Add VERSION to protocol NOW

You are at the perfect moment.

Add:

HELLO|client=viewer|version=2

Server can respond:

PROTO|version=2

Then:

Version 1 → inference fallback

Version 2 → STREAM_STATE authoritative

If you skip this now…

You will regret it later.

Every serious protocol eventually needs version negotiation.

⭐ HIGH VALUE Improvement - Create a State Contract

Put this table into your protocol documentation (e.g. PROTOCOL_REFERENCE or README):

Server State Viewer Must Do
ACTIVE decode + render
RECONFIGURING pause decoder / show buffering
PAUSED keep socket alive
ERROR reconnect

This eliminates ambiguity forever.

One More Architectural Insight (Important)

Your repo emphasizes resiliency:

"The system is designed as a state machine. It expects network failures and recovers gracefully."

To fully achieve that:

👉 Viewer must become dumb about state.

Smart clients cause distributed bugs.

Dumb clients + authoritative server = stable systems.

You are moving in exactly that direction.

🚨 One Warning (Most Engineers Miss This)

When should server send ACTIVE?

NOT when encoder starts.

NOT when STREAM_ACCEPTED is sent.

👉 Send ACTIVE only after first keyframe is transmitted.

Otherwise viewer enters STREAMING...

...and shows black.

⭐ Ideal Streaming State Machine Diagram

                    TCP CONNECT
                        │
                        ▼
                  SOCKET_CONNECTED
                        │
                        ▼
                    AUTHENTICATING
                        │
             AUTH_OK    │   AUTH_FAIL
                 ▼      │
           NEGOTIATING  │──────────► DISCONNECTED
     (caps / resolution / bitrate)
                 │
                 ▼
            STREAM_ACCEPTED
                 │
                 ▼
           RECONFIGURING

(decoder reset / CSD / surface ready)
│
first keyframe received
▼
STREAMING
│
┌────────┼─────────┐
▼ ▼ ▼
NETWORK_LOSS SERVER CLIENT_BG
RECONFIG
│ │ │
▼ ▼ ▼
RECOVERING
(request keyframe,
wait for CSD)
│
▼
STREAMING

ANY STATE ─────────► DISCONNECTED

🔥 The 7 States You Should Standardize

Do NOT invent more unless absolutely necessary.
1️⃣ DISCONNECTED

No socket.

Triggers:

app start

socket closed

heartbeat timeout

fatal protocol error

Viewer action:

✅ show reconnect UI
✅ stop decoder
✅ release AudioTrack

2️⃣ SOCKET_CONNECTED

TCP is alive but handshake not done.

Viewer must NOT:

❌ create decoder
❌ allocate buffers

This prevents leaks during reconnect storms.

3️⃣ AUTHENTICATING

Challenge-response.

Timeout recommendation:

authTimeout = 5s

If exceeded → disconnect immediately.

Prevents half-open sockets eating threads.

4️⃣ NEGOTIATING

Exchange:

CAPS

SET_STREAM

encoder profile

bitrate

Do not stream yet.

This is where MANY systems accidentally start sending frames.

You already avoided that — good sign of a maturing architecture 👍

5️⃣ RECONFIGURING ⭐ (Most Important State)

Occurs when:

recording starts

resolution changes

bitrate tier changes

encoder restarts

camera restarts

surface recreated

Viewer behavior:

pause decode
flush codec
wait for CSD
wait for keyframe

NOT optional.

6️⃣ STREAMING

Only valid when ALL are true:

✅ decoder started
✅ surface ready
✅ CSD applied
✅ keyframe decoded

If one breaks → leave this state immediately.

Never “hope it recovers”.

Hope is not an architecture 🙂

7️⃣ RECOVERING

Triggered by:

frame gap > watchdog threshold

packet loss spike

decoder error

server restart

missing keyframe

Viewer sends:

REQ_KEYFRAME

Server should respond within:

< 1 second

Otherwise restart encoder.

⭐ Recommended STREAM_STATE Messages

Do not overcomplicate. Prefer numeric codes (Improvement #1): 1=ACTIVE, 2=RECONFIGURING, 3=PAUSED, 4=STOPPED. Implementation uses e.g. `STREAM_STATE|2|epoch=N`.

Conceptually:

STREAM_STATE|RECONFIGURING (or code 2)
STREAM_STATE|ACTIVE (or code 1)
STREAM_STATE|PAUSED (optional, code 3)
STREAM_STATE|STOPPED (code 4)

Avoid string-only names like:

❌ STARTING
❌ READY
❌ PLAYING

They become ambiguous later.

🔥 CRITICAL Upgrade I Recommend (Not Optional)
👉 Add STREAM_EPOCH

You already flirted with this idea — now formalize it.

Why?

Late packets from previous encoder configs are EXTREMELY common in mobile pipelines.

Without epoch → decoder corruption.

Example:
STREAM_ACCEPTED|epoch=7
STREAM_STATE|ACTIVE|epoch=7
VIDEO_FRAME|epoch=7

Epoch is included inside STREAM_ACCEPTED (not only in STREAM_STATE) so that if control messages reorder under load, the accept is unambiguously tied to an epoch—bundling reduces ambiguity (production trick).

Viewer drops frames where:

frameEpoch != currentEpoch

Boom — 50% of weird decoder bugs disappear.

⭐ State Transition Authority
Server Drives:

RECONFIGURING

ACTIVE

STOPPED

Viewer Drives ONLY:

DISCONNECTED

RECOVERING (watchdog)

Never let viewer declare STREAMING.

That is how ghost states are born.

🔥 One More Upgrade (Senior-Level Recommendation)
👉 Collapse RECOVERING + RECONFIGURING

Many elite streaming stacks treat them as the same.

Example:

Netflix mobile pipeline
WebRTC internals

Both essentially mean:

decoder not safe yet

You can keep both for UI clarity — but internally they often map to one pipeline behavior.

⭐ Production Watchdog Values

Use these unless your telemetry says otherwise:

heartbeat interval: 2s
heartbeat timeout: 6s

frame stall detection: 1.5–2.5s

keyframe request retry: every 1s
max retries: 5

After that:

👉 reconnect.

Not optional.

🚨 Biggest Mistake to Avoid Next
❌ Dual Authority

Example of what NOT to do:

Viewer:

if noFrames → RECOVERING

Server:

STREAM_STATE=ACTIVE

Now your UI flickers forever 🙂

Always prefer server state when present.

Fallback ONLY if silent.
