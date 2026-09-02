# :dsl

The verbs a test is written in. Teleport a player, click a slot, read a block, take a screenshot,
record the screen.

Every one of them is built out of the same `server { }` and `client { }` calls a suite uses -- this
module applies the compiler plugin to itself. That is deliberate dogfooding: if a gameplay verb
cannot be written with the primitives, the primitives are not good enough yet.

## What is in it

Top level (`dsl/`) is the public surface, one file per subject:

| | |
|---|---|
| `Player.kt` | `teleport`, `lookAt`, `lookAtPlayer`, `waitForPlayer`, `giveItem`, `positionOf` |
| `Input.kt`, `ClientInput.kt`, `Interaction.kt` | keys, mouse, `breakBlock`, `useBlock`, `attack`, `chat` |
| `Screens.kt`, `PlayerInventory.kt`, `InventorySlot.kt` | open screens, drag between slots, read what is in them |
| `World.kt`, `BlockSpec.kt` | `build { at(pos) { ... } }`, and reading blocks back |
| `Assertions.kt`, `AssertMode.kt` | `assertThat`, `assertBlock`, and how long to keep trying |
| `Ui.kt` | window size, and what is drawn over a client |
| `ClientScreenshots.kt` | `makeScreenshot(name)` |
| `Recording.kt` | `record(client, file, options) { }` -- see below |
| `TickLoop.kt` | `serverTickLoop { }`, the unit of animation |
| `Orbit.kt` | `orbitPlayer`, built on the tick loop |

`dsl/mc/` is the half that touches Minecraft directly, kept behind its own package so a dedicated
server never resolves a client-only type. `dsl/mixin/` and `dsl/input/` are the hooks.

## The mixins, and why each one exists

Ten entries in `e2e_dsl.mixins.json`: two accessors and two invokers that only widen access to
something private, and six that change behaviour. None of the six is decorative:

| | |
|---|---|
| `MinecraftMixin` | Tells a test client it has focus. A run has several clients and a person looking at one, so at most one window is focused -- the rest would quietly drop every mouse event a test sent them |
| `KeyboardHandlerMixin`, `MouseHandlerMixin` | Enter synthetic input where the GLFW callbacks do, so nothing downstream can tell the difference |
| `InputConstantsMixin` | Key names |
| `GuiOverlayMixin` | Draws the framework's own layers above the HUD, screens and toasts |
| `GameRendererMixin` | The end of the frame, which is where a recording takes its picture |

Real input is dropped by `InputGate` while a client is under test, so being "focused" grants a person
nothing.

## Screen recording

`record("alex", "fight.mp4") { ... }` films one client for exactly the block it wraps. The frame
never reaches the CPU: Minecraft's render target is a `GL_RGBA8` texture, which is what NVENC takes
as packed 32-bit RGB, so it is flipped the right way up on the GPU, copied device to device into the
encoder's memory, and encoded there.

`dsl/mc/record/` holds the machinery -- the recorder, the frame clock that decides which rendered
frames become recorded ones, and the flip. The encoder itself is [`:capture`](../capture/README.md).

Needs an NVIDIA GPU. Without one the recording is refused with a line in the client's log and the
test carries on.
