# World In A Jar

*A whole persistent world, small enough to carry.*

World In A Jar lets players craft glass jars containing real, persistent, enterable miniature
worlds. Place a jar to see its live interior as a tiny scene, enter through its portal to build
and survive at full size, then pick it up without losing anything inside.

## Highlights

- Persistent, portable jar worlds with names, occupants, interiors, and portal state.
- Two-way live previews: outside viewers see the miniature interior, while occupants see the
  surrounding real world.
- Enterable portals for players, mobs, items, and projectiles.
- Modular jars that can be attached and detached while preserving their interior spaces.
- `/jar back` recovery for jars continuously held by a player.
- Optional teleport and respawn isolation, plus tick-budgeted previews and world edits.

## Requirements

- Paper 1.21.11, 26.1, 26.1.1, 26.1.2, or 26.2
- Java 25

ProtocolLib is optional and required only for the `protocol` entity-preview backend on 1.21.11.
DH Support is also optional; when installed, World In A Jar caps Distant Horizons LOD rendering
while a player is inside a jar, preventing LOD terrain from conflicting with the interior preview.
