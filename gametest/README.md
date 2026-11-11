# EdenMod gametests

Runs a real Minecraft client (software-rendered, headless) with EdenMod loaded, puts it in
scenes that stress EdenMod's per-frame hooks, and **fails if EdenMod's share of the render
thread exceeds a budget**. It exists because this class of bug is invisible to unit tests:
a `WeakHashMap` keyed by per-frame render states and a deep-hashing cache key both passed
every test and only showed up as frame spikes in game.

## Run it

```
./gradlew runClientGameTest
```

Needs a display. On a desktop it opens a window; headless use `xvfb-run -a ./gradlew runClientGameTest`
(Linux, with Mesa's software GL installed). CI does exactly that in `.github/workflows/gametest.yml`, see "CI" below.
The first run downloads game assets. Results land in `build/gametest/` (`results.json`, `client.log`, `screens/*.png`).

### NixOS

NixOS has no system-wide GL or X11 libraries, so the game's bundled GLFW cannot create a window
("GLFW error 65542: GLX: Failed to load GLX"; the build says so). Give the *gametest task* those
libraries, and only that task, with a Gradle init script in your home directory, so nothing else on
the system sees a changed `LD_LIBRARY_PATH` and no file is needed in this repo. With home-manager:

```nix
home.file.".gradle/init.d/gametest-gl.gradle".text = ''
  allprojects {
    tasks.matching { it.name == 'runClientGameTest' }.configureEach {
      environment 'LD_LIBRARY_PATH', '${lib.makeLibraryPath (with pkgs; [
        stdenv.cc.cc.lib libglvnd openal alsa-lib systemd
        xorg.libX11 xorg.libXcursor xorg.libXext xorg.libXi xorg.libXinerama xorg.libXrandr xorg.libXxf86vm
      ])}:/run/opengl-driver/lib'
    }
  }
'';
```

`/run/opengl-driver/lib` is where NixOS keeps the GPU driver. After that, plain
`./gradlew runClientGameTest` works from any shell.

## What runs

| suite | what it checks | takes |
|---|---|---|
| `perf` | EdenMod's share of render-thread time in stress scenes, against budgets | ~2.5 min |
| `behaviour` | baby mode leaves things near a mounted rider alone | seconds |
| `ui` | every screen the mod defines opens, fits the window and is never drawn larger than the GUI scale, at two settings (the smallest window, and 1080p at GUI scale 2); screenshots saved | ~30 s |

Run a subset while iterating: `./gradlew runClientGameTest -Pgametest.suites=ui` (or `perf,behaviour`).

## Cost

Measured on a 12-core machine (the full run, wall time / CPU time): 292 s / 747 CPU-s before tuning,
184 s / 484 CPU-s after. What changed: the perf scenes use a 640x360 window (software rasterisation
is native time and is not part of the measurement), a shorter warm-up, and a frame count per scene
that follows how many samples it yields. The perf world is ~90% of the run; the other suites' worlds
take seconds, so sharing one world between suites would save almost nothing.

Capping llvmpipe's threads (`LP_NUM_THREADS`) was measured and dropped: on 12 cores it cut CPU by about
20% but made the run 20-60% slower, and on 2 cores it changed nothing.

## Small CI runners

On 2 cores the world used to fail to load ("Timeout loading world", stuck at "Preparing spawn area:
16%"): the client redraws the loading screen flat out in software and starves the server thread.
`support/Worlds.java` therefore loads a small world (render distance 4) at 20 fps in a tiny window,
then restores the normal settings. Pinned to two CPUs with `taskset -c 0,1` the screens suite went
from a load timeout to passing. Every suite opens its world through `Worlds.open`.

## Running on a GPU

`./gradlew runClientGameTest` renders on whatever display it finds. On a desktop that is your GPU, in a
visible window, so a developer needs nothing special. The virtual display (Xvfb, which CI uses) has no
GPU, so it renders in software. The build prints which one was used ("Rendered on: ... (GPU)" or
"... (software)").

The game is always muted. Add `-Pgametest.background=true` to also hide the window: it is created
hidden (a small mixin in `support/mixin`, so it never flashes while the game loads) and the run does
not get in your way. The screens suite took 46-53 s and about 120-150 CPU-seconds on a GPU,
against 62 s and 296 in software.

Caveats: a tiling or otherwise opinionated window manager may refuse the resizes the screens test
asks for (the test fails with a clear message rather than checking the wrong size), and the perf
budgets are per renderer, see below.

Short names: `./gradlew runBgClientGameTest` is the same run with the window hidden.

## What runs in the game, and what does not

Only what needs a real client runs here: rendering (the perf scenes), real entities in a real world (the
behaviour check), and real fonts and widgets (the screens). Everything that can be checked without a
game is a plain JUnit test in `test/`, which runs in seconds on every build. Before adding something
here, ask whether it could be one of those.

Examples of what was kept out of the game on purpose:
- The layout rules (`EdenPanelLayoutTest`): every panel size in every window and GUI scale, so the game
  only has to confirm that the real screens follow them, at two settings.
- Where Escape goes: this is made a compile-time guarantee instead of a test.
  `EdenReferenceScreen.onClose` is final and returns to the screen's parent, every screen must pass its
  parent explicitly (modal screens pass `null` on purpose), and a screen that has something to save
  first overrides `beforeClose()`. Nobody can override `onClose` into going somewhere else, or forget
  the parent. The game only presses a real Escape on three screens: one that must go back to its parent
  and the two modal progress screens (gifting, ally), which must stay open because a background task is
  driving the game menus behind them.

## Screens are discovered, not listed

`ui/ScreensGameTest` scans the mod for every non-abstract `Screen` under `tel.eden.mod`, so each screen
is opened at least once. A screen with a `(Screen, EdenModClient)` or no-argument constructor is opened
automatically, so a new screen is tested the day it is written. Anything else makes the test fail until
it is added to `MANUAL` (how to build it) or `EXCLUDED` (why it cannot be tested, in writing). Entries
for screens that no longer exist fail too, so neither list can go stale.

## Sound

The client is silent from process start: the run sets `ALSOFT_DRIVERS=null`, OpenAL's no-output
backend, so nothing is played and no audio device is needed (the log says "OpenAL initialized on
device No Output"). Muting in the game would only start once a test begins.

## CI

A pull request gets the fast checks only (`build.yml`: formatting, unit tests, build). The gametests take
minutes, so they run when code lands on `master`: `build.yml` calls `gametest.yml` after the build
passes, and the release job needs both, so nothing is published unless the gametests passed. They can
also be run by hand from the Actions tab (`gametest`), on any branch.

A failing gametest does not stop a merge, because it only runs afterwards. If that ever matters, GitHub's
merge queue (a `merge_group` trigger plus a "Require merge queue" ruleset) would run them on the merged
result before it lands; the repository is eligible, and it is left out for now.

The release job releases whenever `master`'s `mod_version` has no GitHub release yet. It does not check
whether the commit changed `gradle.properties`, so a version bump whose gametests failed is released by
the next `master` commit that passes, tagged at that later commit.

## Reproducing CI locally

CI runs in Ubuntu with the real `xvfb-run`, which makes Loom take a different code path than a desktop
run (it wraps the client in `xvfb-run` itself; the build switches that off, see `useXvfb` in
`build.gradle`). That difference is invisible on a machine without `xvfb-run`, so reproduce CI in a
container before trusting a change to the run itself. With podman (rootless is fine):

```
# Dockerfile: the workflow's packages plus a JDK
FROM docker.io/library/ubuntu:26.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git unzip openjdk-21-jdk-headless \
    xvfb libgl1-mesa-dri libglx-mesa0 libegl1 libxrandr2 libxcursor1 libxi6 libxxf86vm1 libopenal1 && rm -rf /var/lib/apt/lists/*

podman build -t edenmod-ci .
podman volume create edenmod-gradle      # keeps Gradle's caches, so only the first run downloads
podman run --rm --init --cpus 4 -e CI=true -v "$PWD":/work -v edenmod-gradle:/root/.gradle -w /work edenmod-ci \
    bash -c 'xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew runClientGameTest --no-daemon'
```

`--init` matters: without it `xvfb-run` becomes the container's PID 1 and waits forever for Xvfb's
ready signal, which looks like a hang with no output. Copy the project without `build/` and `.gradle/`
first if you do not want the container writing into your tree. A run takes about 4 minutes on 4 CPUs.

## Memory

Measured on a 12-core Linux box with `-Xmx2g` for the client: the game JVM sits at about 1.4 GB
resident (steady from ~20 s in) and Gradle's own single-use JVM at about 1.2 GB (capped at 2 GB by
`gradle.properties`); Xvfb is ~20 MB because the software GL memory lives in the game process.
Plan for about 3.5 GB in total. A run takes about 5 minutes, most of it the scenes.

## What is measured

Per scene, after a warm-up, over 1500 frames:

| metric | meaning |
|---|---|
| `modSharePercent` | **The gate.** Share of render-thread *Java* samples (JFR, 2 ms) with a `tel.eden.mod` frame on the stack. Native time (the GL driver) is excluded, so it means the same on a laptop GPU and a CI runner. |
| `hitchRatio` | p99 render-thread CPU time per frame divided by the median. A coarse stall tripwire. |
| `topModFrames` | Which EdenMod methods the samples landed in. Read this first when a budget fails. |
| median / p99 / max CPU ms, `allocKiBPerFrame` | Recorded for trend-watching, not gated. |

CPU time is used instead of wall time because the gametest advances the client in lockstep with
the test thread, so wall-clock gaps include waiting that is not the mod's.

## Budgets: `perf-budgets.json`

Two metrics are gated per scene, each with its own budget, and budgets are per renderer (`software` is
CI, `gpu` is a desktop GPU).

**Mod allocation per frame** (`maxModAllocKiBPerFrame`): the bytes the render thread allocates in code
under `tel.eden.mod`, measured with JFR allocation samples and attributed to the mod method that
allocated them. This is the metric to trust. It does not depend on how fast the machine is, so it keeps
its meaning on a slow CI runner, and it names the method. The limits are the same for both renderers.

| scene | unfixed code | fixed code | budget |
|---|---|---|---|
| idle | 0 | 0 | 1.0 KiB |
| displays | 3-5 KiB | 0 | 2.0 |
| displays-baby-mode | 95-125 | 0 | 10 |
| ghost-players-baby-mode | 99-110 | 0 | 10 |
| inventory-of-potions | 5-8 | 0 | 3.0 |
| ground-items | 67-83 | 5-9 | 15 |

Measured on a GPU and in the CI-equivalent container (see "Reproducing CI locally"); both agree.

**EdenMod's share of render-thread Java time** (`maxModSharePercent`): a coarse net for CPU-bound
regressions. Use it with care, because it depends heavily on the machine: the same unfixed code read
9-17% on a 12-core desktop but about 1-3% in the CI-equivalent container, where most of the frame is
native software rendering. So on CI it only catches large regressions. The budgets were chosen between
the fixed and unfixed readings of each environment:

| scene | software: fixed | software: unfixed | software budget | GPU: fixed | GPU: unfixed | GPU budget |
|---|---|---|---|---|---|---|
| idle | 0.0-0.6% | 0.0-0.3% | 2.0% | 0.0-1.3% | 0.0-0.5% | 2.0% |
| displays | 0.0-1.1% | 1.3-9.8% | 3.0% | 0.2-0.6% | 5.2-8.7% | 3.0% |
| displays-baby-mode | 0.1-4.8% | 1.0-13.5% | 7.0% | 5.0-5.4% | 15.8-16.6% | 9.0% |
| ghost-players-baby-mode | 1.1-3.5% | 3.4-14.4% | 7.0% | 4.0-5.3% | 16.5-16.8% | 9.0% |
| inventory-of-potions | 1.1-4.2% | 4.8-7.6% | 7.0% | 1.8-2.2% | 8.9-9.3% | 4.5% |
| ground-items | 0.0-1.9% | 0.8-4.6% | 4.0% | 0.1-1.0% | 4.3-4.5% | 2.5% |

The software inventory budget is deliberately loose (7%): GitHub's runner showed 4.2% for the fixed code
and the unfixed code reads 4.8-7.6%, so the share cannot tell them apart there. The allocation budget
catches that regression instead. The software numbers come from my desktop's software renderer, the CI
container and one GitHub run; a different GPU may need its own `gpu` numbers.

The hitch ratio (p99 frame / median frame) only counts when the slowest frame is also at least 15 ms.
On a GPU a normal frame is about 1 ms, so a 4 ms frame would otherwise read as a 4x stall. Each scene
skips its first 60 frames, which is when starting the JFR recording costs a few slow frames.

A scene with fewer than `minRenderSamples` Java samples fails rather than passes, since a share from
a few samples is noise.

## Order matters

`PerfClientGameTest` is listed before `BehaviourGameTest` in `fabric.mod.json` on purpose. With
the behaviour test first, the baby-mode scenes read about twice as high (6.5-8.4% instead of 3.0-3.5%)
in the same JVM, most likely because it changes how the JIT compiles the hot path. Keep the
measurement scenes first, in a fresh JVM.

## Waiting in tests

Wait for the condition you need (`context.waitFor(client -> ...)`), never a fixed number of ticks. A
two-core runner can leave the server seconds behind, so a fixed wait that works on a fast machine
fails there. Remember that integer `summon` coordinates are centred on the block (3 becomes 3.5).

## Adding a scene

Add a hook that runs once per entity, item or slot per frame -> add a scene to
`PerfClientGameTest` that puts many of those on screen, then add its name to `perf-budgets.json`
(a scene without a budget fails). Run it on the code *before* your change as well, and set the
budget between the two readings.

## Limits

This measures how much of the Java render work is EdenMod's, in synthetic scenes. It does not
measure real GPU time, other mods, or Wynncraft's own behaviour, and it cannot see visual
correctness. When something feels slow in game, a JFR recording of the real client is still the way to find out why.
