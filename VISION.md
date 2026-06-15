# Haven Product Vision

## Thesis

Haven is a **thin-client operating system for distributed compute, storage, and presence**. The pocket device in your hand is not the computer — it's the point of presence from which you reach the computers, the files, and the agents that do the work. Those computers and files are scattered: a workstation under the desk, a VM in a datacenter, a folder in Google Drive, a camera feed at home, a screen sharing something on the other side of the room. Haven is the single lens through which you see and operate on all of it.

The last decade convinced us that "desktop" and "files" are local nouns. The next one undoes that. Storage lives on whatever is cheapest. Compute runs wherever the data is. Humans, phones, laptops, and AI agents all take turns driving the same shared workflow. Haven treats that world as the default case instead of the exception.

## Identity

The strongest identity Haven has is: **the open-source, privacy-first mobile workspace.** JuiceSSH is dead. Termius went proprietary. ConnectBot is unmaintained. Haven is the only active GPL-licensed terminal app with modern Compose UI, hardware key support, a local Linux environment, a unified cloud file browser, a real media toolchain, and a native GPU-accelerated Wayland desktop — in one APK, no accounts, no telemetry.

The GPL/privacy audience chooses Haven *because* it's open source. Every security choice — encrypted credentials, biometric lock, TOFU host keys, FIDO2 support, local storage only — reinforces this identity. That is the moat and the brand.

## The four primitives

A coherent OS abstraction reduces to four things: a place to keep stuff, a place to run stuff, a way to move bits between them, and a way to be present at all three — to reach in and operate them, and to be reached back by them. Haven is organized around those four primitives. The first three — **Namespace**, **Runtime**, **Gateway** — are the *substrate*: each holds state. The fourth — **Presence** — is the *surface*. It cuts across the other three, because every primitive needs a touchable surface, and it is itself **bidirectional**: humans and agents reach *in* to operate the substrate, while the substrate's surfaces — and the agents working in them — reach *back out* to the human's attention. Presence is a primitive of a different kind, and it carries its own rules — detailed as primitive 4 below.

### 1. Namespace — one filesystem across the universe

The file browser is the **unified namespace**. Local storage, SFTP, SMB, 60+ cloud providers via rclone, and PRoot-mounted Linux rootfs (Alpine / Debian / Arch / Void) are all surfaced as tabs in the same UI, with the same operations: copy, move, rename, delete, open, stream, convert, share. Cross-filesystem copy/move is a first-class operation — drag a file from Google Drive into an SFTP server and it goes through Haven without a local round-trip if the backends support it.

Where files live is an implementation detail. Actions — convert, encrypt, stream, share — apply wherever the file is, not only to local copies. Rclone's HTTP serve + Range requests mean a 5 GB cloud file can be transcoded, previewed, and streamed without ever touching the phone's disk.

### 2. Runtime — any shell, any window, anywhere

The **runtime** is the place you actually run things. Haven exposes four kinds:

- **Local shell** — a full Linux userland on the phone via PRoot — Alpine, Debian, Arch, or Void, installed side-by-side, each with its native package manager and dev tools. Anything that runs on Linux arm64 — language runtimes, build toolchains, AI/agent CLIs, ad-hoc scripts — runs here without Haven knowing or caring what it is.
- **Remote shell** — SSH, Mosh, Eternal Terminal, and Reticulum transports, with session persistence via tmux/zellij/screen, auto-reconnect, session restore, and a color-coded tabbed terminal that treats all four the same way.
- **Remote desktop** — VNC (with VeNCrypt/TLS), RDP (via IronRDP, with EGFX graphics-pipeline support), tunneled through SSH when you want the wire encrypted.
- **Local desktop** — two flavours, both running on-device. A native GPU-accelerated Wayland compositor (labwc/wlroots) in Haven's own process, GLES2-composited via AHardwareBuffer; and a multi-distro desktop manager that installs and runs full X11 (Xfce4 / Openbox) or nested-Wayland (Sway) environments inside a PRoot rootfs and surfaces them through the in-app VNC client. A real Linux desktop on the phone, distinct from any remote screen.

All four are runtimes in the OS sense: processes with input, output, a filesystem, and a network. Haven's job is to make switching between them feel like switching between terminal tabs on a desktop OS.

### 3. Gateway — the network is the substrate

The **gateway** is how runtimes talk to each other and to the outside world. Haven bundles the primitives of a programmable network:

- **Port forwarding** — Local (`-L`), Remote (`-R`), and Dynamic (`-D`, SOCKS5 proxy server) over any active SSH session, managed live from the UI.
- **ProxyJump** — multi-hop tunnels through bastions and jump hosts, visualised as a tree.
- **Transport proxies** — SOCKS5/SOCKS4/HTTP for reaching `.onion` or corp-restricted endpoints.
- **Overlay tunnels** — WireGuard, Tailscale, and Cloudflare Access let a profile reach hosts that have no public IP, without consuming the system VPN slot. Each tunnel is per-profile, ref-counted across protocols (SSH + VNC + SFTP through one handle), and survives network transitions. WireGuard is also Haven's default carrier for the agent endpoint (see §1a).
- **Single Packet Authorization** — a native fwknop SPA / port-knock client (`core/spa`: `FwknopPacket`, `SpaSender`, `SpaConfig`) sends one encrypted packet that opens a firewall port just long enough to connect. A service can stay fully port-closed to the internet until Haven knocks — reachability without exposure.
- **Network-aware reconnect** — NetworkMonitor triggers an immediate reconnect when WiFi/cellular/VPN flips, instead of waiting for a TCP timeout.
- **Mesh** — Reticulum transport for off-grid connectivity.
- **Service publishing** — the rclone media server, the HLS streaming server, and the Wayland socket (via Shizuku) turn the phone itself into a host that other devices on the LAN can reach.

You should be able to reach any service from any runtime with one configuration step, and the connection should survive network transitions without your workflow fraying.

### 4. Presence — the surface, in both directions

Every primitive needs a touchable surface, and Presence is that surface. But it runs in **two directions**, and that is what makes it a primitive rather than a coat of paint. One direction is the rule for *where Haven owns the surface at all*. The other is that the surface is *shared with agents*, who both drive it and reach back through it. A VNC session of "a screen on the other side of the room," an inline overlay an agent pushes a chart into, the terminal you read your agent's progress in — all of these are presence.

#### Build vs delegate

The decision of whether Haven builds a surface or hands off to the host OS is not aesthetic — it's structural. The rule:

> **Build the presentation where the user needs to compose that primitive with another one inside Haven. Delegate to the OS where the handoff is clean and the user just wants a destination.**

That rule produces a sharp dividing line:

**Haven builds its own presentation for:**

- **The terminal** (termlib fork with touch-first gesture layer, smart clipboard, OSC 8/9/52/133 wiring, toolbar, shell-integration features). There is no Android terminal primitive, and a terminal that can't be composed with SSH sessions, port forwards, file browser actions, and Wayland tabs in the same app isn't worth much.
- **The file browser** (Compose-based, tabbed, unified across six backends). Android has DocumentsProvider but it's fragmented, read-mostly, and doesn't support SFTP/SMB/rclone as first-class peers. Haven's file browser is where every other primitive becomes actionable — the single most important composition surface in the app.
- **The VNC and RDP clients** (JSch-tunneled VNC with VeNCrypt, IronRDP-backed RDP). Standalone VNC/RDP apps exist but can't see Haven's SSH sessions, port forwards, or connection profiles. Composability forces them in-app.
- **The native Wayland desktop** (labwc/wlroots compositor running in-process, GPU-composited via AHardwareBuffer). This is the most technically novel surface — there is no Android primitive for a Linux desktop. It's also the surface that lets Haven double as a workstation on big-screen outputs.
- **Embedded app windows** (a single guest GUI app under `cage`+wayvnc, surfaced live in an in-app overlay — `PresentationHost`, `EdgeIconDock`, `PipController` — with Picture-in-Picture, an edge-icon dock, and multiple concurrent restartable windows). One Linux app's window lives *in* Haven, composable with the terminal and file browser, instead of a tab-switch away.
- **The convert dialog, the port forward dialog, the preview/filter UI, the connection edit dialog.** These are pure composition surfaces — they wire two or three primitives together and only exist because the primitives live in one process.

**Haven delegates to the OS for:**

- **Media playback.** `ACTION_VIEW` + MIME type hands the file to VLC, MX Player, or whatever the user prefers. Android's intent contract is solid, the ecosystem is strong, and users already have opinions about players. Haven is the transport and transformation layer; playback is downstream. The rclone HTTP server URL, the local file URI, and the HLS playlist URL all survive the handoff.
- **HTML5 HLS playback.** Chrome + hls.js via `ACTION_VIEW` on the server URL. Writing a video element wrapper in-app would buy nothing.
- **File opening for non-media.** Tap a PDF → system PDF viewer. Tap an image → system gallery. Tap a text file → whatever the user installed. FileProvider + intent is the contract.
- **System authentication.** BiometricPrompt for app lock; the OS credential dialog for FIDO2.
- **Notifications, shortcuts, share targets.** All the places Android provides a standard surface.

The build-vs-delegate rule has a useful test: *if the user would want to invoke a second primitive while looking at this view, we have to own the view.* You want to copy a line from a terminal into the port-forward dialog → same app. You want to open a media file in a player → different app is fine. You want to drag a file from one backend tab to another → same app. You want to watch the file → different app is fine.

This is also why certain features are explicitly not going to be built (see "Scope boundaries" below): a text editor, a media player, a chat UI. Those would be presentations of nothing — they don't compose with Haven's primitives, they just duplicate work the OS already supports well.

#### Shared with agents

The surfaces Haven builds are not only for human eyes. An AI agent operating alongside the user — whether running in PRoot on the device or in a remote shell Haven is connected to — needs to **work with and within what the user sees**. That imposes three concrete requirements on every presence surface Haven owns:

**Observable state.** Every view has state: which backend and path the file browser is on, what's in the terminal's current scrollback and which command wrote it, which file is loaded in the convert dialog with what filters, which port forwards are active and bound to which ports, what the connection status of each session is. That state must be exposed in a form an agent can read — the same StateFlow pattern the Compose UI already subscribes to. If a human can see it, an agent must be able to query it. The endpoint of this principle is that the screen itself becomes a readable resource: an agent that can pull *what is rendered right now* as a snapshot needs no human to describe the phone to it. Haven exposes terminal and remote-desktop snapshots today, a `capture_haven_ui` tool that returns Haven's *own* rendered screen, and that same capture as the addressable `ui://haven/screen` MCP resource (`resources/read`) — so an agent can pull what's on screen without even a tool call.

**Actionable API.** Every action a tap can perform must also be a function an agent can call. Haven's ViewModel methods — `convertFile`, `streamFile`, `playMediaFile`, `navigateTo`, `setPortForwardingDynamic`, `connect`, and their peers — are the vocabulary that both humans and agents use to operate the primitives. There is no "agent-only" bypass layer. If the agent can do it, the user can watch it happen in the UI. If the user can do it, the agent gets a tool-use handle for it.

**Reachable surface.** The sharing runs both ways. As well as reading state and calling actions, an agent can *initiate* a surface to reach the user: push an image or sound into an inline overlay (`present_media`), bring a single guest app's live window to the foreground (`present_app`), raise a system notification (`raise_notification`), queue a message into the user's terminal view (`queue_self_message`), or request a decision through the consent sheet. The human is present at remote screens; the agent, symmetrically, is present at the human's attention. Every reach-out is gated by the same per-action consent and rendered on a surface the user is already looking at — it is a channel *to* the user, never a channel *around* them.

These three rule out two failure modes:

1. **Silent automation** — the agent acting through a hidden channel the user can't see. Every agent operation, in either direction, is reflected in the same UI surface a human would use, so the user can observe, interrupt, and take over mid-workflow. "Haven is doing something" and "the screen is showing something" must always be the same thing.
2. **Divergent state** — the agent's model of "what's happening" drifting from the user's. Both subscribe to the same state flows, so what Haven shows is ground truth for both.

The practical consequence is that the agent transport — a tool-use server (MCP or equivalent) that exposes Haven's ViewModels as callable tools over a local loopback socket — is not an optional add-on. It falls out of the build-vs-delegate rule directly: if we build a presence surface because it needs to compose with other primitives, we also need to make that surface addressable from outside the UI process, because an agent is just another caller that needs the same composition. Humans tap, agents call, both observe, and both can be reached — same surface, same state, same truth.

## The host as a privileged peer — brokering the phone's own capabilities

The four primitives reach *outward* — to other machines' files, shells, networks, and screens. But the device in your hand is itself a computer, and it holds capabilities the runtime can't reach on its own: the USB device on the OTG port, the camera, location, sensors, the clipboard, notifications, biometric hardware, the system log. On Android these sit behind the framework's permission model and SELinux; a process inside the PRoot runtime — and therefore an agent running in it — cannot open them directly. A Linux program that expects raw `/dev` nodes and udev just hits a wall.

The resolution is a pattern, not a one-off workaround: **when a runtime or an agent is blocked on a host capability, Haven brokers it.** Haven is the one component that is a real Android app, with framework access and a path to user consent. It opens the capability the Android way, gates it behind an explicit prompt, and re-exposes it as a primitive the runtime and the agent use through the same tool-use surface as everything else — humans tap, agents call, both observe. The phone OS is therefore not only a *delegation target* (hand a file to a media player, raise a biometric prompt) but a *source of brokered capabilities* the distributed workflow composes with.

This is shipped, not hypothetical. The USB broker is a complete vertical slice: a device opened via the platform's USB API and surfaced either as a device-specific tool set or as a file descriptor bridged into the runtime, with a userspace proxy socket, hidraw/libudev shims for guest HID and Mono apps, and per-device consent. The same pattern now also brokers the **system log** (`read_logcat`, routed through Shizuku because Haven holds no `READ_LOGS` grant) and **on-device adb reach** over a system VPN (`expose_adb`/`unexpose_adb`). The camera, sensors, location, and clipboard are the same shape. Framed for an agent, these brokered capabilities are its *senses*: the build output and system log it can already read (`read_logcat`), and — as the same broker pattern reaches them — the IMU, camera, and location that would let its model of *what is physically happening* come from the device itself rather than from a human's description. The boundary stays sharp — Haven brokers *access* to the device, it does not become the device's app or reimplement a vendor's tool. And it stays honest about the floor: capabilities the platform only grants with root (raw bus access, udev, kernel modules) are a power-user, root-gated path, never the default. The non-root broker is the common case, because the point is to work on the phone the user actually has.

This is the build-vs-delegate discipline pointed inward: build the broker where the runtime needs a capability the platform won't hand to a non-app process; delegate where a clean handoff already exists. Together with the four outward primitives, it is what makes Haven a *home* for an agent on the device as well as a *bridge* to everything beyond it.

## The integration thesis — composition is the product

The four primitives are not the product. What you can *do by composing them* is the product. A coherent OS is one where they compose without friction. Haven's design target: every workflow below should be one flow inside the app.

- Tap a 4K MKV sitting in Google Drive → ffmpeg reads it over HTTP from rclone VFS → the frame preview appears in 3 seconds → tweak brightness → pick "H.264, back to cloud" → the converted file appears in the same Drive folder without ever touching local disk.
- SSH to the workstation → forward port 5901 → tap the VNC profile that targets `localhost:5901` → desktop opens in the same app, keyboard and clipboard shared.
- In the local PRoot shell, start whatever agent CLI you use, pointed at a project directory. It reads files, runs `git push` over the SSH agent you forwarded from your laptop, and you watch it work on the same screen where the SSH tab lives.
- Copy a log directory from an S3 bucket to an SFTP server: long-press → cut → switch tab → paste. Rclone does the server-side copy when possible; otherwise Haven streams it through.
- Stream a video from a cloud folder to the Chromecast across the room via HLS, copy the LAN URL from the snackbar, paste it into a message to your partner's phone so they can watch too.

The same composition points the other way too — at Haven developing itself. These last entries are the frontier the work is heading toward, not one-tap flows today:

- The dev agent builds Haven on the workstation over SSH, pulls the fresh APK through the namespace, installs it on the very phone it is operating (`install_apk_from_backend`), then *drives the new build and diffs the screen against its expectation* to file a verdict — a release-verify cycle with no human hand on the device. The install half ships today; the drive-and-diff half is now real — `capture_haven_ui` sees Haven's own rendered screen and `tap_haven_ui` / `swipe_haven_ui` drive it, device-verified on a Pixel 8 Pro (install → capture → tap-navigate → swipe → re-capture), with the capture also exposed as the addressable `ui://haven/screen` resource. Text entry into Haven's own fields is deliberately *not* part of this — agents enter data through the ViewModel tools (`create_connection` and peers), not by simulating typing into the app's own chrome.
- Further out: a reflex policy holds the phone's camera on the workstation monitor through two servos on a printed dock, so the agent reads its own viewing angle through the namespace the way it reads any other stream — the point where presence stops being only software.

None of these require leaving Haven, installing a second app, or running a `curl | ssh` incantation. Each uses two or more of the four primitives — and the last two show the same primitives, composed, reaching back to build the app itself. This is the thesis.

## The agent era — agents are a first-class user

LLM agents are a new class of actor, and they need the same primitives a human does: credentials, shell access, a filesystem, a network, a surface. An agent is just a very persistent process that wants to SSH, read files, run commands, transform media, and occasionally show its operator something — exactly what Haven already mediates for a human. Haven has no opinion on which agent you run: any CLI that speaks Linux conventions is as welcome as any other, and the app ships no vendor-specific integrations, installers, or brand surfaces.

What this means in practice:

- **Agents in the local runtime**: PRoot is a first-class place to run whatever AI CLI, local dev tools, and language runtimes the user wants. No root, no Termux, no rebuild needed. Haven neither knows nor advertises which CLI you're running.
- **Agents in a remote runtime**: SSH to the workstation where your real agent lives, keep the session alive across network drops, and come back to a session that remembers what the agent was doing.
- **Credentials that humans and agents share**: one encrypted keystore, SSH agent forwarding so the remote agent can use keys that never leave the phone, host key TOFU so a compromised gateway can't silently inject itself.
- **Files that humans and agents both operate on**: the unified namespace means "the project folder" is one path whether the agent is running in PRoot, on the workstation, or pulling from cloud storage.
- **Senses that ground the agent**: build output and the system log are readable today (`read_logcat`); the IMU, camera frames, and a snapshot of Haven's own screen are the next shape of the host-broker (see "The host as a privileged peer" and §1a). The aim is that an agent's model of *what is physically happening* comes from the device itself, not from a human's description of it.
- **Shared presence (both directions)**: agents address Haven's ViewModels through a tool-use transport (see "Presence", primitive 4). They *drive* surfaces the user is watching — open the convert dialog, toggle a port forward, start a stream, change the active SFTP path — *and* they *reach back* to the user — push media into an overlay, bring a guest app's window forward, raise a notification, ask through the consent sheet. Every action and every reach-out lands on a surface the user can see; no hidden automation, no divergent state. The UI is ground truth for both.
- **Observation**: the terminal tab, the file browser, and the persistent notification give a human operator a clear view of what their agent is doing and where. Haven is the dashboard, not a black box.

Haven is *not* building an AI assistant. It's building the layer that makes AI assistants useful wherever a human wants to point them — mobile, distributed, multi-backend. The agent sees the same OS abstraction the human does, and operates through the same surfaces the human uses, so the human always keeps the wheel.

## Consent tiers — from per-action to standing policies

The shipped trust model has two working levels: reads are free, writes raise a non-skippable consent sheet (with an opt-in "allow for N minutes" window and a Settings-level paired-client bypass for a client the user trusts). That is right for one-shot actions and wrong for *reflexes* — a control loop ticking many times a second cannot raise a sheet per tick, and a build watchdog that needs permission to notice a crash is not a watchdog. The model therefore grows to four tiers, each fully audited:

- **Tier 1 — observe.** Read-only tools and resources. No prompt. *(✅ shipped.)*
- **Tier 2 — act once.** One-shot writes. Per-action consent sheet. *(✅ shipped.)*
- **Tier 3 — standing policy.** A declarative grant the agent proposes and the user installs once (`create_standing_policy` — the consent sheet spells out the full scope), against a declared scope (exactly which tools, optionally pinned arguments), a rate ceiling, and an expiry (≤24 h). A kill-switch lives on the Agent activity screen; every covered call is still written to the audit table; beyond the rate ceiling, calls fall back to per-action prompts. *(✅ shipped — scope-binding, rate ceilings, expiry, and the kill-switch are built; the policy binds to one named client and can never cover the policy tools themselves, `install_apk_*`, or `unpair_mcp_client`.)*
- **Tier 4 — irreversible & physical.** Anything that spends money, moves mass, replaces the running app, or edits the Memory/constitution (next section). Always per-action, never standing, no matter how often it recurs. *(Forward-looking.)*

The tier is a property of the **verb, not the caller**. A human tapping a Tier-4 action *is* the consent; an agent calling it raises the sheet. Reflexes never escalate themselves: a Tier-3 policy that wants a Tier-4 action must surface it to a human like everything else.

## Memory — the agent's persistent constitution

An agent that works on the same project across many sessions needs somewhere durable to keep what it has learned and the rules it operates under — and, consistent with "the UI is ground truth, no hidden channel," that store should be a **surfaced artifact the human can read and edit**, not state buried inside a model's context. Haven's role here is not to ship a model or a memory service; it is to host the agent's *constitution* — its notes, decisions, and standing instructions — as a human-auditable, human-editable document set the agent maintains. Because a write to it reshapes all of the agent's future behaviour, editing the constitution is a **Tier-4** action: irreversible in spirit, always surfaced. This is forward-looking; the nearest thing today is the agent↔agent self-messaging substrate sketched in `docs/haven_convo_prototype.py`.

## The self-hosting loop — Haven develops Haven

Haven began as a vibe-coding experiment: an agent on a workstation, a repo on GitHub, and a human holding the phone to see whether the build works. Two shipped verbs already let that loop reach back into the device — `install_apk_from_url` / `install_apk_from_backend` deploy a build onto the phone the agent is operating, and `enable_wireless_adb` / `expose_adb` open the door to driving it. The loop can *act on its own body* today.

What was missing is symmetry: the loop must also *perceive* and *drive* its own body. The first cut of both now exists as MCP tools — `capture_haven_ui` returns Haven's own rendered screen (a `PixelCopy` of the foreground window, read-only and `FLAG_SECURE`-aware), and `tap_haven_ui` / `swipe_haven_ui` inject pointer input into that window in the same pixel space the capture reports — so an agent can install a build, see the result, and drive it through the same surface a finger would. Injection is refused while a consent prompt is showing, so it can never self-confirm. Capture and the two pointer verbs are device-verified on a Pixel 8 Pro (install → capture → tap-navigate → swipe → re-capture diff). The capture is exposed both as the `capture_haven_ui` tool and the addressable `ui://haven/screen` resource (`resources/read`), sharing one consent gate. Tier-3 standing policies now close the prompting gap: a drive-and-diff loop installs one scoped, rate-capped, expiring grant (`create_standing_policy`) instead of prompting per tap, every covered call still audited, revocable from the Agent activity screen. Text entry into Haven's own fields is *not* on any list: agents enter data through the actionable-API tools (`create_connection`, `update_connection`, `set_preference`, …), not by simulating typing — and Haven's Compose fields take text through an IME `InputConnection` that raw key injection doesn't reach anyway. Until the rest lands the loop runs under Tiers 1–2 with a human eye on the screen, and any Tier-4 act — replacing the running app, editing the constitution — is surfaced for a human's tap. The agent↔agent half of the loop is prototyped in `docs/haven_convo_prototype.py`.

## Development priorities

The thesis is clear; the work is making it feel seamless. Priorities are ordered by leverage, not by effort.

### Next iterations

The four threads getting attention next, each detailed in its section below:

1. ✅ **Warm the Reticulum extremity** — *shipped (v5.59.10).* The mesh now carries file transfer (browse/download/upload over the rnsh command-exec substrate) and a tunnelable port (`-L`/`-D`), not just a shell. The remaining throughput work — a persistent sftp-subsystem path, today it is one Link per directory op — is a follow-up, not a seam.
2. **Close the namespace gaps** — age file encryption and SFTP/SMB media streaming, the two universal actions still missing on some backends (§2).
3. **Consolidate the agent-presence layer** — lift the reach-back tools (`present_media`/`present_app`/`raise_notification`/`queue_self_message`/consent) into one documented bidirectional model. (MCP `resources/*` for the screen has landed — `ui://haven/screen`; §1a.)
4. **The MCP plugin bus** — a second cross-protocol verb plus discovery of external MCP-publishing Android apps (§1b).

### Growth diagnostic — where each primitive sits today

A growth plan needs a diagnosis. Mapped onto the four primitives and the seams between them, Haven currently looks like this:

- **Heart — Namespace (SSH, SFTP, rclone): three organs, one operation code path.** SSH and SFTP share a JSch session per profile (`SshSessionManager.SessionState` reuses the SSH client and exposes `sftpChannel` alongside the shell channel). Rclone, SFTP, SMB and the local backend now route through one `FileBackend` interface (`#126` stages 1-3) for list/delete/mkdir/rename/readBytes/writeBytes — a new universal action (encrypt, inspect, hash) now lights up every tab at once. The remaining heart work is feature-shaped (universal actions, ffmpeg-with-libssh for SFTP/SMB streaming) rather than refactor-shaped.
- **Shoulders — Runtime + remote Presence (VNC, RDP, SMB, i18n): exercising well.** Tunnel-aware protocols open SSH port-forwards on demand and a `tunnelDependents` set tears the shared tunnel down cleanly when the last consumer leaves (`#121`). VNC and RDP now share a `RemoteDesktopSession` abstraction (`#128`). Localisation reaches twelve locales with the lint-warning safety net (`#125`). EGFX (ClearCodec + RemoteFX Progressive) shipped (v5.24.69) and is verified against Windows Server 2025; the remaining reach is GNOME Remote Desktop interop (it needs server-redirection following, #117), more codecs, and per-locale changelogs rather than missing seams.
- **Extremities — Gateway/Reticulum: warming.** `ReticulumTransport` now exposes `execCommand` beyond `RnshShellSession`, and the mesh carries the same load-bearing surface SSH has: the file browser does list/download/upload over Reticulum and `-L`/`-D` forwarding tunnels TCP/SOCKS through it (v5.59.10), built on the rnsh command-exec substrate rather than a parallel stack. Remaining: a persistent sftp-subsystem path for throughput (today it is one Link per directory op — small-file oriented) and file sync.
- **Face — Presence/agent (MCP): opinionated, and now bidirectional.** Per-backend tools collapsed into one tool with a backend argument (`#127`). Cross-tab verbs landed against `AgentUiCommandBus` (`navigate_sftp_browser`, `focus_terminal_session`, `open_convert_dialog_with_args`). The first cross-protocol verb shipped — **`compose_workspace`** opens an entire workspace (terminal + file-browser + desktop + Wayland items) in one call through the same `WorkspaceLauncher` the user's tap drives. And the reach-back direction is now live: `present_media`, `present_app`, `raise_notification`, and `queue_self_message` let the agent surface things *to* the user. No single-purpose client has the substrate to compose those primitives or to reach the user through the same consent stack; this is the moat. Consolidating the reach-back tools into one model is next-iteration #3.

Priorities below are ordered by what this diagnostic implies.

### Juxtapositions — completed arc

The 2026-04-20 audit found six places where Haven's surface looked unified but the implementations underneath were parallel. Each instance taxed future work: a new universal action took N implementations to land, a new backend had to plug into N call sites, and the tax compounded. All six have now landed:

- ✅ **File browser per-backend dispatch (largest instance).** `FileBackend` interface in `core/sftp` unifies list/delete/mkdir/rename/readBytes/writeBytes across local, SMB, rclone, and SSH (`#126` stages 1-3). The `when { isLocal -> … isRclone -> … }` switch in `SftpViewModel` is gone.
- ✅ **MCP tools per backend.** Per-backend `list_sftp_directory`/`list_rclone_directory`/etc. collapsed into single tools that take a backend argument (`#127`). Agent vocabulary is shorter and uniform.
- ✅ **VNC and RDP as parallel remote-desktop stacks.** `RemoteDesktopSession` abstraction shipped (`#128`). Universal verbs (record, paste, scale-to-fit, attach-tunnel) are now one implementation, not two.
- ✅ **Keystore as three stores.** Unified `Keystore` interface over SSH keys (Tink AEAD), FIDO2 credentials, and encrypted profile credentials (`#129` stages 1-5). One audit screen, one biometric gate with 30s session-unlock window, one `Keystore.fetch()` primitive.
- ✅ **Foreground service hardcoded to known transports.** `ForegroundSessionParticipant` interface; managers register themselves (`#130`). Adding a new transport no longer requires editing `SshConnectionService`.
- ✅ **Session managers as dispatch, not abstraction.** Unified `Session` abstraction; `list_sessions` MCP tool covers all seven transports through one interface (`#131`).

Already unified before the audit (no work needed): network discovery (`NetworkDiscovery` merges mDNS, ARP, Tailscale, and SMB scan into one `StateFlow<List<DiscoveredHost>>`); the ffmpeg convert pipeline (URL-driven, no per-backend special-case); `ConnectionLogRepository` writes (one Room table, callers populate per-layer, which is fine).

The architectural payoff is now banked: every new universal action lands once, every new backend lights up everywhere. Remaining work in §1-§5 is feature-shaped, not refactor-shaped.

### 1. Composition polish — make the primitives snap together

Whenever two primitives meet, there should be zero friction. Current gaps:

- **SFTP/SMB media** should work through the same HTTP-streaming trick as rclone so convert/preview/stream/tap-to-play work for every backend, not just rclone + local. Building an ffmpeg-with-libssh would unlock this in one move. (Next-iteration #2.)
- **Agent forwarding UX** — the plumbing exists; the story of "forward my phone's keys to the remote agent and be able to trust it" needs to be a dialog, not a config file.
- ✅ **Workspace profiles** — shipped. "Save current as workspace" snapshots active terminal sessions (SSH/Mosh/ET/Reticulum/Local), file-browser tabs (SMB), remote desktops (RDP), and the Wayland tab into a named bundle; one tap reopens the same composition. `WorkspaceLauncher` is the orchestration substrate the matching `compose_workspace` MCP verb in §1b is built on.
- **Desktop ↔ file browser ↔ terminal** — agent-driven cross-tab verbs landed (`navigate_sftp_browser`, `focus_terminal_session`, `open_convert_dialog_with_args`). Human-driven equivalents — drag a file from the SFTP tab into the native Wayland compositor, copy output from a terminal into the convert dialog — are the next layer.
- ✅ **rclone fused into shared operation code** — done via `FileBackend` (`#126`). All four backends route through one operation interface; new universal actions land once.
- ✅ **Reticulum carrying more than shell** — shipped (v5.59.10). File transfer (browse/download/upload over the rnsh command-exec substrate) and Reticulum-as-tunnelable-port (`-L`/`-D`) turned the extremity into a working limb. The Reticulum mesh is the only transport in Haven that survives full internet loss, so this is a unique capability, not a duplicate path. Remaining throughput work — a persistent sftp-subsystem path — is a follow-up.

### 1a. Agent transport — shipped (v5.24.81), since broadened

The shared-surface idea is now a concrete transport. Haven's local loopback MCP server exposes tools across read, write, and reach-back paths, and every non-read call surfaces a non-skippable bottom-sheet consent prompt before the action runs. What landed:

- **Tool-use server** — MCP / JSON-RPC over HTTP loopback (port range 8730–8739), Streamable HTTP stateless transport. Disabled by default; toggled in Settings → Agent endpoint.
- **State inspection tools** (no prompt) — `list_connections`, `list_sessions`, `list_directory` (one tool, backend arg), `stream_sftp_file`, `read_terminal_scrollback`, `play_file`, `get_app_info`, `list_rclone_remotes`, `stop_stream`.
- **Action tools** (per-action consent) — `open_local_shell`, `send_terminal_input`, `add_port_forward`, `remove_port_forward`, `upload_file`, `delete_file`, `disconnect_profile`, `convert_file`, `set_terminal_font_from_url`, `open_developer_settings`, `enable_wireless_adb` (Shizuku-gated), `install_apk_from_url`, `install_apk_from_backend`.
- **Cross-tab UI verbs** (against `AgentUiCommandBus`) — `navigate_sftp_browser`, `focus_terminal_session`, `open_convert_dialog_with_args`, `connect_profile`. Direct demonstration that humans tap, agents call, both observe.
- **Reach-back tools** (added since — the agent→user direction) — `present_media` (image/sound into an inline overlay), `present_app` (a single guest GUI app's live window in the overlay), `raise_notification` (a system notification on the agent's behalf), `queue_self_message` (a line into the user's terminal view). All consent-gated; all rendered on a surface the user is already looking at.
- **Local-desktop / PRoot lifecycle** (added since) — `list_distros`, `install_distro`, `delete_distro`, `list_desktop_environments`, `install_desktop`, `uninstall_desktop`, `start_desktop`, `stop_desktop`, `read_desktop_log`, `inspect_proot`, `get_proot_install_log`, `list_desktop_sessions`, `run_in_proot`. Drives the whole multi-distro proot + desktop pipeline over MCP, so integration tests run agent-side instead of by hand.
- **rclone sync + tunnels + gateway** (added since) — `start_rclone_sync`, `save_sync_profile`, `get_rclone_sync_status`, `list_live_tunnels`, `create_tunnel`, the SPA/port-knock tools (`set_spa`, `test_spa`, `set_port_knock`), and the adb-reach broker (`expose_adb`/`unexpose_adb`), plus host-capability brokers (`read_logcat`, the USB tools).
- **Audit and consent** — `AgentConsentManager` with foreground fail-closed semantics; `AgentAuditRecorder` writes every call to a Room table with redacted args; in-app "agent active" chip on the Connections top bar lights up on recent activity; `AgentActivityScreen` is the dashboard.
- **Discovery and transport** — **WireGuard is now the default carrier for the agent endpoint**: the device serves MCP on its WireGuard LAN IP and a remote client points there directly, surviving network transitions; the SSH `-R 8730` rule is kept as a fallback. Settings exposes the endpoint URL, an MCP-config JSON snippet, and the "Tunnel through SSH profile…" shortcut for the fallback path.

What's still ahead in this lane:

- **Consolidate the agent-presence layer** — the reach-back tools (`present_media`/`present_app`/`raise_notification`/`queue_self_message`/consent) shipped as point features. Lift them into one documented bidirectional presence model with a consistent consent + audit surface, so "the agent showed me something" and "the agent asked me something" are one coherent channel rather than several. (Foregrounded next-iteration #3.)
- **Own-UI perceive + drive — landed and device-verified.** `capture_haven_ui` returns Haven's own rendered screen (read-only, `FLAG_SECURE`-aware) and `tap_haven_ui` / `swipe_haven_ui` inject pointer input in the captured pixel space — the screen-resource + input-verb pair the self-hosting loop needed, verified end-to-end on a Pixel 8 Pro (install → capture → tap-navigate → swipe → re-capture). The capture is exposed both as the `capture_haven_ui` tool and the file-shaped `ui://haven/screen` resource (`resources/read`), so an agent can pull a snapshot without a tool call. **Tier-3 standing policies** complete the lane: a many-tap loop runs on one installed grant instead of a prompt per tap (see "Consent tiers"). **Text entry into Haven's own fields is deliberately out of scope** — agents enter data through the ViewModel tools (`create_connection` and peers) per the actionable-API rule, and Compose's IME-mediated `InputConnection` means raw key injection wouldn't reach those fields anyway. Terminal and remote-desktop snapshots remain the other screen resources.
- ✅ **Tier-3 standing policies — shipped.** `create_standing_policy` / `list_standing_policies` / `revoke_standing_policy`: a scoped (tools + optional pinned args), rate-capped, expiring (≤24 h) grant bound to one client, installed by the user's tap on a consent sheet that spells out the full scope. Enforcement sits at the consent gate (covered calls skip the prompt, everything else falls through, every call still audited); the kill-switch lists live grants on the Agent activity screen. The policy tools themselves, `install_apk_*`, and `unpair_mcp_client` can never be covered — reflexes don't escalate themselves.
- **Sensor / IMU / camera brokers** — extend the USB + `read_logcat` host-broker pattern to the device's own senses, so an agent's model of physical state comes from the device (see "The host as a privileged peer").
- **More cross-tab agent verbs** — the bus pattern is proven; remaining surfaces (port-forward dialog with args, connection-edit dialog, key-deploy flow) are mechanical extensions.

### 1b. MCP as a bidirectional protocol — Haven as host *and* client

Section 1a covers Haven publishing an MCP server: agents reach into Haven's primitives. The mirror image is Haven becoming an MCP *client* — discovering and consuming capabilities published by other Android apps that speak the protocol. Together, the two halves turn MCP from a single-direction agent transport into a generic capability bus, with Haven as one peer among others. This is the foregrounded next-iteration #4.

This is not the same as shipping an AI assistant or bundling a vendor's agent CLI (both excluded under "Scope boundaries"). Haven still has no model, no chat UI, no prompt language — only the primitives, the surfaces, and the ability to compose those primitives with capabilities that other locally installed apps choose to expose. The user is still the one pointing the agent at things; the agent is still operating through observable UI; the trust model is still per-action consent. What changes is what's in the agent's vocabulary.

Concrete shape:

- **Discovery without dynamic code.** Installed apps that publish MCP servers register via a documented Android `<intent-filter>` plus manifest `meta-data`. Haven enumerates them, surfaces them in Settings → Agent endpoints, and lets the user enable specific capability bundles. No bytecode is loaded from outside the APK; the F-Droid reproducible-build rule stays satisfied because each plugin is a separately source-built APK.
- **Composition with Haven primitives.** When external MCP capabilities are present, Haven's MCP server can advertise them as part of its own capability list (or proxy them transparently) so an agent connected to Haven sees one tool surface that combines Haven primitives with external capabilities. Example workflows: *Reticulum-fallback file sync* — Haven's SFTP plus an external "watch this directory" capability; *RDP clipboard ↔ SFTP path* — Haven's RDP clipboard plus an external "translate path" capability; *agent journal* — Haven's audit log plus an external "summarise" capability.
- ✅ **The first cross-protocol verb — shipped.** `compose_workspace` was the chosen one: takes a saved workspace id, dispatches every item (TERMINAL → WAYLAND → FILE_BROWSER → DESKTOP) through the same `WorkspaceLauncher` the human tap drives. The agent's vocabulary now contains a verb no single-purpose MCP client could express, because no single-purpose client has the substrate. The next named candidates (`mirror_directory_with_fallback`, `move_session`) stay on the list — the moat is established and the next ones grow it.
- **Audit and consent extend unchanged.** External capabilities go through the same `AgentConsentManager` per-action prompt and the same `AgentAuditRecorder` Room table. The user can't tell from the consent sheet whether a tool is a Haven primitive or a plugin's; they don't need to.

The bidirectional direction is consistent with the four-primitives frame: Haven's primitives stay scoped, but the *agent vocabulary* over those primitives grows. The plugin system lives at the orchestration layer, never at the bytecode layer.

### 2. The namespace as the action surface

The file browser is Haven's highest-leverage surface because it's where every backend converges. Every action that applies to a file should be available on every file, regardless of where it lives. (This whole section is the foregrounded next-iteration #2.)

- **Universal action set** — convert, preview, stream, play, encrypt, share link, copy path, inspect metadata — consistent across local, rclone, SFTP, SMB, PRoot. Gaps (e.g. encrypt isn't implemented yet, stream doesn't work on SFTP/SMB, metadata inspection is shallow) are bugs against the thesis.
- **age file encryption** — end-to-end encryption for files in any backend; keys live in Haven's keystore, operate wherever the ciphertext lives.
- **Trim / cut / resolution picker** — finish the media toolchain so the convert dialog is a real mobile NLE, not just a transcoder.

### 3. The runtime story — PRoot is the agent host

The local PRoot rootfs is the differentiator nobody else ships. It's where an agent can run persistently on the phone without leaving Haven.

- **Curated dev stacks** — one-tap installers for common language runtimes (Python, Node.js, Rust, Go), pre-tested, following the existing `DesktopEnvironment` enum pattern. Haven ships the runtime; the user brings whatever runs on it, agent CLIs included.
- **sshfs inside PRoot** — mount remote filesystems so local tools (and local agents) operate transparently on remote files.
- **Storage management** — rootfs images grow; show disk usage, offer cleanup, support external storage.

### 4. The Wayland desktop — a second runtime

Haven's native Wayland compositor is the most technically differentiated piece of work. Keyboard, GPU, window management, and virgl GL passthrough are shipped, and the multi-distro desktop manager adds a complementary path: full window-managed environments (Sway via wayvnc, plus X11 Xfce4/Openbox) running inside any installed rootfs. Remaining work:

- **Wider nested-compositor support** — Sway (wlroots/pixman) runs headless in PRoot; GLES-only compositors (Hyprland/aquamarine, niri/smithay) can't initialise a backend there because the Android GPU isn't driveable by Mesa in proot — they're offered but GPU-limited (tracked on #162). A working software/virgl GL path for the nested case is the unlock.
- **Brokered GPU acceleration into the proot cage.** Guest Mesa in proot can't reach the GPU — `/dev/dri` is permission-denied to the app uid and no Mali node is exposed — so nested-Wayland and cage GL fall back to llvmpipe (device-measured: glxgears ~140 FPS software under Sway+Xwayland in the cage on a Pixel 8 Pro). The app's *only* GPU path is Android's own EGL/Vulkan loader, so the unlock is the host-broker pattern (see "The host as a privileged peer"), not device-node passthrough: a **Haven-launched `virgl_test_server`** (virglrenderer holding a surfaceless EGL/GLES context on the GPU, running in Haven's *Android* process where `libEGL` reaches Mali) with the guest's Mesa pointed at it via the `virpipe`/`virgl` Gallium driver over a socket in cacheDir (bound to the guest's `/tmp`) — the same shape as the USB and wayvnc shims re-exposing an Android capability into the runtime. **Licensing is clear:** virglrenderer and Mesa are MIT, the Termux build glue is Apache-2.0 — all compatible with AGPL and F-Droid build-from-source. **The guest half needs no submodule** — the distro's own Mesa already carries virgl (confirmed: `virtio_gpu_dri.so` in the Arch rootfs); we only set `GALLIUM_DRIVER=virpipe`. **Only the host renderer is built** — a submodule of upstream virglrenderer plus a small Haven Android-EGL patch (surfaceless context instead of a DRM node), cross-built with the NDK like wgbridge and the wayvnc shim. (No canonical Termux repo to submodule — their server is upstream virglrenderer + downstream Android patches, so Haven carries the patch itself.) **The probe *is* the first integration slice**, not a freebie: a drop-in of Termux's prebuilt server can't work — Android's app sandbox stops a foreign process from sharing a socket into Haven's proot, and the server must run outside proot to get Android EGL — so the minimal test is the NDK server launched by Haven, re-running glxgears to see whether `GL_RENDERER` flips off llvmpipe. Device-specific: Mali rules out the Adreno `zink`+Turnip route, so virgl-over-Android-EGL is the path; full-pipeline accel additionally needs wlroots on a GPU renderer, so app-level GL accel is the first win.
- **GL version ceiling and forward options (verified on a Pixel 8 Pro).** The cage GL broker shipped (v5.59.63): app-level OpenGL in a Sway cage now runs on Mali (`GL_RENDERER = virgl (Mali-G715)`) instead of llvmpipe. But it is capped at **GL 2.1**, and this is a hard ceiling for *desktop* GL: Android's EGL exposes only **GLES**, and virgl's GLES→desktop-GL translation caps the *compatibility* profile at 2.1 — so legacy/desktop-GL apps (glxgears, older games, most CAD viewers) cannot exceed it. A **dead-end was verified**: bumping the host virgl EGL context from ES2→ES3 (`vrend_winsys_egl.c`, rebuilt + device-tested) did **not** raise the desktop-GL version (glxgears stayed 2.1); a higher GLES context can only lift *GLES-native* caps, which the software-Xwayland cage couldn't even measure (`glxinfo` → `X_GetImage BadMatch`, `eglinfo` hangs). Forward options in payoff order: **(1) venus + guest zink — GL-3.2 context reached, app rendering blocked by a vtest+venus fence gap.** Built a venus-enabled `virgl_test_server` + self-locating `virgl_render_server` (NDK cross-build, `-Dvenus=true`, render-server path via `/proc/self/exe`), `apt install mesa-vulkan-drivers` provides the guest venus ICD (`libvulkan_virtio.so` + `virtio_icd.json`). **Device-verified the transport works**: `vulkaninfo` in the cage reports a real Vulkan device `Virtio-GPU Venus (Mali-G715)`, `DRIVER_ID_MESA_VENUS`, apiVersion 1.4 — i.e. guest Vulkan → vtest socket → host venus → Android `libvulkan` → Mali, **with no `/dev/dri`/virtio-gpu kernel device**. **The allocation wall was found, fixed, and cleared — then a deeper blocker surfaced.** First root cause (debug render server + captured stderr): `vkr: minigbm_allocation is not enabled` → `vkBindBufferMemory2 CS error` → a FORTIFY abort — virglrenderer's venus (`src/venus/vkr_device_memory.c`) allocates exportable device memory only via **minigbm**, which needs a `/dev/dri` render node the sandbox lacks. **Fix built + device-verified:** an **AHardwareBuffer allocator** was added to vkr's device-memory path (`#elif defined(ENABLE_AHB_ALLOCATION)` — AHB `BLOB` → `getNativeHandle` → dup'd dma-buf fd → `VkImportMemoryFdInfoKHR{DMA_BUF_BIT_EXT}`; the host Mali Vulkan was confirmed to advertise `VK_EXT_external_memory_dma_buf`, so no `/dev/dri` needed). **This works** — memory now allocates + binds, the abort is gone, and the pipeline advances to the host processing the **full venus command stream** (instrumented: 308 commands consumed cleanly). **Coherency was ruled out** as the next suspect: the host ring monitor reads the guest's tail and processes everything over the shared AHB memory — expected, since guest+host are CPUs on one ARM SoC (two cached dma-buf mmaps are HW-coherent; the ring's C11 atomics rely on that). **Root-caused (deep dive #2, device-instrumented) = a GPU-completion-signalling gap in vtest+venus, NOT the GPU, memory, or AHB.** A minimal headless Vulkan probe (`/tmp/venusfence.c`) isolated it: through venus, an **empty `vkQueueSubmit`+fence completes**, but a `vkCmdFillBuffer` (real GPU work) **hangs** — for *every* memory type, including the **device-local-only type1 that does NOT use the AHB import path** (so AHB is exonerated as the cause). The **same probe cross-compiled for Android and run from `adb shell` against the real Mali ICD passes for all types** — and the virgl GLES path already proved in-process GPU jobs work — so the **GPU and driver are fine**. Render-server instrumentation showed the host executes the work correctly: `vk->QueueSubmit` returns `VK_SUCCESS` and a host-side `vk->WaitForFences` on the submit fence returns `VK_SUCCESS` (GPU genuinely finishes) — yet the guest still spins in `MESA-VIRTIO: stuck in fence wait`. The signal never gets *back* to the guest. Why: (a) the guest's render submits arrive over vtest as `VCMD_SUBMIT_CMD2` batches with **`ring_idx=0`** and mostly `sync_count=0` (ring-kick pings, no GPU fence requested); the one context fence that does arrive uses `ring_idx=0` → `vkr_context_submit_fence`'s `ring_idx==0` branch **retires immediately on the CPU timeline, bypassing the GPU-wait sync queue**; and (b) venus's own ring sync (`vkr_transport.c` `vkr_ring_write_extra`/`submit_virtqueue_seqno`/`wait_ring_seqno`) is a **CPU-roundtrip ordering** mechanism, **never driven by actual GPU-fence completion**. So the guest polls for a GPU-completion signal the host never emits. A crude workaround — a host-side blocking `WaitForFences` right after `QueueSubmit` — **does fix standalone `vkWaitForFences`** (it serialises completion into the ring advance) but **does not fix swapchain rendering** (es2gears + glmark2 still hang), because that path waits via semaphores/WSI, not a plain fence. **This is the "fundamental vtest+venus gap" confirmed:** a real fix means implementing GPU-fence-driven completion signalling in the vtest+venus path (what the kernel virtio-gpu driver provides and vtest does not) — upstream-virglrenderer-scale work, not a quick wiring change. **The headline metric was still hit:** with the workaround, `eglinfo` under zink-on-venus reports **`OpenGL core profile version: 3.2` (renderer `zink Vulkan 1.4 (Virtio-GPU Venus Mali-G715)`)** — past the virgl **2.1** ceiling — proving the modern-GL *context* is reachable; only sustained app *rendering* is blocked by the fence gap. (Mali also misses two zink base feats `fillModeNonSolid`/`shaderClipDistance`, which cap zink at GL 3.2 rather than 4.x — a separate, lesser issue.) **Status: the AHB allocator + venus build patches + fence diagnostics are uncommitted working-tree changes; not shipped. Conclusion: venus-in-the-cage executes GPU work and exposes a GL-3.2 context, but is blocked from real app rendering by an unimplemented GPU-completion-fence path in vtest+venus — the documented next frontier.** **(2) GLES-renderer cage compositor** — `WLR_RENDERER=gles2` wlroots over virgl, so the *compositor* (not just the app) is GPU-accelerated (full-pipeline). **(3) Xwayland in the cage by default** — shipped, so X11/GLX apps run at all. Note the broker is GLES-only today; it does **not** unblock the GLES-only nested compositors (Hyprland/aquamarine, niri/smithay still fail `CBackend::create()` — they need a DRM render node, not a GL client driver).
- **GL client passthrough** — virgl ships for labwc-native; venus/Vulkan (option 1 above) is the next layer.
- **Standalone socket** — let external clients (Termux, chroot, foreign runtimes) connect.

### 5. Network resilience and security as brand

- **Background keepalive resilience** — Doze mode / app standby / battery optimisation is the biggest source of "session died" complaints. Document, automate, and expose clear recovery actions.
- **Per-profile authentication** — high-security connections require auth each time.
- **Audit log UI** — surface ConnectionLog so privacy-conscious users can verify the app's behaviour.
- **Secrets-clean logs** — automate the scrub of credentials from verbose logs and crash reports.

## Scope boundaries — what Haven is not

A tight scope is how a small project stays coherent. Haven deliberately does not:

- **Build an editor** — vim/nano/micro in PRoot or on the remote shell is the editor. Building an in-app text editor is a tar pit.
- **Build a media player** — the OS already has VLC, MX Player, etc. Haven hands off via intents. We are the file transport and transformation layer, not the playback layer.
- **Reimplement tmux/zellij** — split panes, scrollback search, session persistence are session-manager features. Haven integrates with them via SessionManagerRegistry instead of competing.
- **Build provider-specific features** — rclone is the abstraction. No Google Drive-specific sharing UI, no Dropbox versioning, no S3 object lifecycle panel. The provider list stays uniform.
- **Build collaboration** — shared sessions, voice, screen sharing. Out of scope for a single-developer project and orthogonal to the identity.
- **Optimise for tablets/desktops first** — get the phone-in-one-hand experience right before chasing form factors.
- **Ship an AI assistant** — Haven provides the substrate agents run on; it does not ship its own model, API, or chat UI. The user brings the agent.
- **Bundle, advertise, or name any specific vendor's agent CLI** — no "Install Claude Code" button, no "Configure OpenAI" section, no Anthropic/OpenAI/Google branding or endorsements anywhere in the app, the docs, the ROADMAP, or the README. Haven is a generic Linux environment plus a generic MCP endpoint; what you point at it is your private choice and none of the app's business. Corporate neutrality is part of the GPL/privacy identity and is non-negotiable.

## Architectural direction

Think of Haven as three layers, each of which must remain small and sharp:

1. **Substrate primitives** (Namespace, Runtime, Gateway). Each has a clean Kotlin API and wraps exactly one underlying technology per function. No cross-layer leakage: the file browser doesn't know ffmpeg exists; ffmpeg doesn't know rclone exists; they meet through HTTP URLs and process stdin/stdout.

2. **Presence** — the surface primitive. The in-app surfaces built where composition demands it (the terminal, the file browser, the VNC/RDP clients, the native Wayland compositor, embedded app windows, and the dialog surfaces that wire primitives together) *plus* the agent reach-back channel that lets a surface be initiated from outside the UI process. Presence is a primitive of a different kind: the substrate three hold state; Presence is how that state is touched and how it — and the agents working in it — reach back. Adding a new substrate primitive — a new backend or a new runtime — should light up every Presence surface for free; if it doesn't, the primitive or the surface is wrong.

3. **Identity and trust** (keystore, screen lock, TOFU, secrets hygiene). This is the cross-cutting layer that earns users' confidence in putting their credentials on the phone in the first place. Every feature must pay its security rent.

Outside those three layers is the Android host, which provides media playback, PDF/image viewing, notifications, share sheet, biometric prompts, and web rendering. Haven explicitly delegates to it for anything covered by the build-vs-delegate rule above. The phone OS is a free lower layer we don't need to rebuild — and, per "The host as a privileged peer," a layer Haven also *brokers into* the runtime: platform-gated capabilities (USB, camera, sensors, location, clipboard, system log) opened with consent and re-exposed as primitives, rather than escalating the guest's privilege.

A public library succeeds not by having every book, but by having the right books, organized well, in a building that's pleasant to be in. Haven's books — protocols, backends, codecs — are sufficient. The work now is in the organisation (composition surfaces, workspace profiles, cross-tab actions) and the building (touch interface polish, gesture reliability, battery-friendliness).

**Width is sufficient. Composition is the opportunity.** The phone is the thin client; Haven is the thin-client OS; the cloud and your servers and your agents are the computer.
