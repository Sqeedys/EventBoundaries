# Event Boundaries

<img src="curseforge/logo.png" alt="Event Areas, with a red no-entry sign over a wooden gate" width="112">

Temporary, invisible movement barriers for staff-run events. Each boundary is a circle in one dimension. An optional height limit turns it into a finite cylinder for a room or floor. Movement uses the player's position at their feet and counts the surface as inside.

## Install

[Download Event Boundaries 0.1.0 for Minecraft 26.3](https://github.com/Sqeedys/EventBoundaries/raw/refs/heads/main/downloads/eventboundaries-0.1.0.jar)

Add `eventboundaries-0.1.0.jar` and Fabric API **0.165.2+26.3** to the dedicated server's `mods/` folder. The pinned runtime is Minecraft Java **26.3**, Fabric Loader **0.20.1**, and Java **25**. Clients join with the matching vanilla protocol. The mod has no client installation, resource pack, or world-generation step. [Fabric server launcher](https://fabricmc.net/use/server/), [pinned Fabric API](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.165.2%2B26.3/).

## Quick start

Stand at the center of an arena and run this command to close entry within 30 blocks for 15 minutes.

```mcfunction
/eventboundary create arena entry 30 900
```

Use `/eventboundary freeze arena` to let current occupants return and `/eventboundary release arena` to reopen entry early.

## Commands

Use `/eventboundary` from the console or an authorized staff account. `eventboundaries:command/view` grants list, info and member pages. `eventboundaries:command/manage` grants every command. Both use the Fabric permission API and fall back to vanilla operator level 4. Command access and movement passes are independent.

| Command | Effect |
| --- | --- |
| `/eventboundary create <id> <entry\|exit\|locked> <radius> <seconds> [height <half_height>]` | Creates the barrier at the execution position, in its current dimension. |
| `/eventboundary list [page]` | Lists active IDs, dimensions, modes and remaining durations. |
| `/eventboundary info <id>` | Shows geometry, remaining duration and membership counts to the invoking staff member. |
| `/eventboundary freeze <id>` | Replaces the reentry roster with currently living online players inside the boundary. |
| `/eventboundary members <id> <roster\|entry\|exit> [page]` | Shows the UUIDs in the selected roster or explicit pass list. |
| `/eventboundary pass <id> <grant\|revoke> <entry\|exit> player <online_name>` | Changes a directional pass using the online player's UUID. |
| `/eventboundary pass <id> <grant\|revoke> <entry\|exit> uuid <uuid>` | Changes a directional pass for an explicit UUID, including an offline player. |
| `/eventboundary release <id>` | Removes the barrier immediately and clears its memberships. |

To set a finite room from console, use `/execute in minecraft:overworld positioned 24.5 65 24.5 run eventboundary create room locked 8 300 height 2`. This covers Y 63 through 67. IDs use 1–32 lowercase letters, digits, underscores and hyphens, beginning with a letter or digit. Radius is 1–1,024 blocks, time is 1–86,400 seconds and half-height is 0.5–1,024 blocks. Limits are 64 active barriers and 256 UUIDs per membership list.

- `entry` lets occupants leave and blocks arrivals. `exit` lets visitors enter and stops occupants from leaving. `locked` applies both directions per player. Create a locked barrier around a group to contain the players already inside.
- Freeze the roster while the intended players are inside. Roster eligibility grants entry and reentry. It leaves any exit lock in force. Manage exits with explicit exit passes. Explicit entry passes and roster entries are independent. Freezing again replaces the whole roster, so do it while everyone you want to retain is present.
- Each overlapping barrier has its own policy. Every relevant barrier must permit a crossing.
- Normal movement checks the path for a complete crossing, including movements whose endpoints are both outside. Teleports check the origin and destination dimensions and positions. An outside-to-outside teleport is allowed when its endpoints cross no relevant boundary. Operator teleports use the moving player's passes. Vanilla `/tp` can report success for a refused teleport, so check the player's position when operating through automation.
- State is stored in the loaded save under `eventboundaries/state.json`. Commands apply after the state is written. Restart preserves active barriers and memberships. Real time runs during downtime and expired barriers stop applying automatically. Invalid state stops server startup with the original file preserved for recovery. Stop the server before restoring a valid state file from backup.

## Safety and limits

Ordinary denied moves stay at the last permitted position. Vehicle movement checks all affected player passengers. Loaded, collision-free recent positions are available for a correction after server-side displacement. Corrections leave terrain intact and avoid generating chunks. Event Boundaries only governs player movement, so mining, placement, combat, items, mobs and other interactions retain normal game behavior across the line.

A login or rejoin inside an entry lock requires a pass or frozen roster eligibility. An unauthorized login or a respawn entering from outside searches for nearby loaded standing space. It checks the current column and up to 32 perimeter columns within eight vertical blocks of the player's position, retrying for up to 20 server ticks. Candidate space must have a sturdy floor, two empty blocks, no collision and satisfy applicable overlapping barriers. Keep the event area near loaded safe standing space. For a large barrier, tall drop or unsafe overlap, the search can exhaust its candidates. When no safe correction exists, the server accepts the current position and privately informs the player and staff. Players who die inside a hold-in can use their vanilla respawn outside. A zone erected around someone already present does not eject them while they stay connected.

Spectator targets and native teleport paths are checked where the server moves the player. Mods that directly mutate positions, dimensions or player lifecycle, and visual or interaction behavior from other mods, need separate compatibility testing. Event Boundaries is intended for managed events where staff can intervene.

The first visit to the End exit portal starts the credits flow before teleportation. An exit lock also blocks this flow while the player occupies the boundary. This guard applies even when a custom setup configures that player's eventual respawn within the End.

## Build and test

Use Python 3.10 or later and a Java 25 JDK.

```sh
python build.py --java-home "<jdk-25-directory>"
```

The build downloads pinned Minecraft and Fabric dependencies from Mojang and Fabric, verifies their hashes, and runs the Java test suite. It prints the SHA-256 and writes the JAR to `build/libs/eventboundaries-0.1.0.jar`. See [verification](VERIFICATION.md) for the dedicated-server test.

## Credits

Created by Sqeedys. [More mods](https://sqeedys.com/mods) · [Sqeedys on YouTube](https://www.youtube.com/@Sqeedys)
