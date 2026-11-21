# Verification

Event Boundaries 0.1.0 targets Minecraft Java **26.3** with Fabric Loader **0.20.1** and Fabric API **0.165.2+26.3**. The build and dedicated tests use Java 25. The tested runtime used Eclipse Adoptium **25.0.5+8** and Loader's bundled MixinExtras **0.5.5**. The build and smoke-test scripts ran with Python **3.13.14**.

Two original builds produced byte-identical copies of `eventboundaries-0.1.0.jar`. All dedicated fixtures described below passed with that JAR:

```text
SHA-256 52346d68df2986424ba6ca8846d09cd129d35da141b439e564291eff367efdb5
```

## Project icon refresh

The [current download checksum](downloads/SHA256SUMS.txt) records the refreshed JAR’s SHA-256, `ef57be3475563ec46e03473c0250d53351acd7d63b5ba0dde2b4417765c87979`. Two builds with Temurin 25.0.5+8 and Python 3.13.14 passed the Java suite and produced byte-identical JARs. Comparing the original and refreshed JARs found one changed entry, `assets/eventboundaries/icon.png`. All 19 other entries had identical contents, including the Java classes. The dedicated-server results below cover the original JAR.

## Reproduce

Use Python 3.10 or later and a Java 25 JDK. The commands download hash-pinned dependencies, compile and test the Java code, package the mod, and then run the packaged JAR. The dedicated test generates a packet report using the pinned Minecraft server and launches fresh, disposable localhost servers. Before running it, read the [Minecraft EULA](https://www.minecraft.net/en-us/eula) and pass `--accept-eula` if you agree. Use a new fixture directory for every run.

```sh
python build.py --java-home "<jdk-25-directory>"
python tests/native_smoke.py --java-home "<jdk-25-directory>" --fixture local/native-smoke-v1 --accept-eula
```

The Java suite checks cylinder geometry, boundary edges, full swept crossings, endpoint-only teleports, separate dimensions, directional permits, roster replacement, wall-clock expiry, release, and persisted state. It rejects malformed state, including missing coordinates and a missing height field.

The dedicated movement fixture checks ordinary-player command refusal and operator access, swept movement, teleport denial, an allowed relative teleport, name- and UUID-based passes, overlapping locks, finite height, roster reentry, release and expiry. It restarts the same fixture to check that a frozen occupant retains access, an unauthorized reconnect is moved to safe loaded terrain, and dimension changes respect exit passes. It also checks vanilla respawn out of a hold-in and correction of a respawn that would enter a keep-out.

The vehicle fixture uses two riders. A passenger without a pass blocks both a movement packet from an authorized driver and an entity teleport. Granting the passenger an entry pass admits both riders. The End exit fixture checks the first-time credits flow with a denied exit and with an exit pass.

The fixtures use vanilla-protocol test clients on synthetic saves. Graphical client behavior, prolonged load, and interoperability with third-party movement and permission mods have yet to be tested.
