# Event Boundaries

Create temporary, invisible barriers for arenas, races, and other server events. Decide who can enter, who can leave, and when the boundary expires.

## Features

- **Entry** lets players leave while blocking new arrivals.
- **Exit** lets players enter while keeping participants inside.
- **Locked** controls both directions. Staff can grant separate entry and exit passes to individual players.
- Freeze the roster to let the players currently inside return later. Release the boundary early or let it expire automatically.
- Set a radius and optionally a height limit. A boundary applies in one dimension, and each overlapping boundary enforces its own rules.

The server checks player movement, teleports, and vehicle passengers. A boundary controls movement only. Combat, building, and item interactions follow the usual game rules. Active boundaries, passes, and rosters survive server restarts, and the timer keeps running while the server is offline.

## Install

Install **Event Boundaries** and **Fabric API 0.165.2+26.3** in the `mods` folder of a dedicated **Minecraft Java 26.3** server running **Fabric Loader 0.20.1** and **Java 25**. Players can join using an unmodified Minecraft 26.3 client. [Fabric server setup](https://fabricmc.net/use/server/)

## Quick start

Stand at the center of an arena and use `/eventboundary create arena entry 30 900` to block entry within 30 blocks for 15 minutes. Use `/eventboundary freeze arena` to allow current participants to come back and `/eventboundary release arena` to open the area early.

Use `/eventboundary` from the console or with staff permissions. Vanilla permission level 4 works by default. Use `/eventboundary list` to see active boundaries and `/eventboundary info arena` for the details. [Full command guide](https://github.com/Sqeedys/EventBoundaries#commands)

## Credits

The project icon is an AI-generated illustration of a perimeter. The actual in-game boundaries are invisible.

Created by Sqeedys. [More mods](https://sqeedys.com/mods) · [Sqeedys on YouTube](https://www.youtube.com/@Sqeedys)
