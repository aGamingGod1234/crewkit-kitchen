# CrewKit set zones (v2, shell owner: set agent)

Coordinates are relative to `CrewkitAnchors.origin` (the north-west floor corner). x runs east 0..27, z runs south 0..21, the floor is y=0 and you stand at y=1. The camera `ck_player` is at (14,5,21), looking north and 25 degrees down. The south side is open.

## Build order and teardown

`SetBuilder.build` places the shell, then calls `KitchenDecor.place(level, origin)` and then `Exterior.place(level, origin)`.

The teardown snapshot covers **x -6..33, y -1..14, z -6..21**. That is the 28×22 footprint plus 6 blocks on the east, west and north sides. Nothing goes south of z=21, because that is where the camera is. Stay inside this box or teardown cannot restore it.

On my branch, `KitchenDecor` and `Exterior` are empty stubs so it compiles. On merge, take your version of each file.

## Hard rule: keep the board sightlines clear

During a run, BoardsFeature draws opaque display panels on the back wall face at z=1.05:
- Budget: x 2.0..11.0, y 1.8..6.7
- Bill: x 16.5..26.5, y 1.6..6.7

The text sits inside those rectangles, including the timer and calls at about y 2.3. From the camera:
- **x 2..11, z 1..4:** nothing taller than y=2.2. So a block at y=1, or a bottom iron trapdoor or carpet at y=2. No brewing stands, cauldrons or pots on top of the counter here.
- **x 16..26, z 1..6:** only blocks at y=1. Exception: x=26, z 2..3 may go up to y=3 (pantry).
- **x 12..15, z 1..4:** free at any height up to y=6 (hood and flue column). A flue can pass through the coffer at (13,7,3) up to y=8.
- **x=11 and x=15 above y=1 at z 2..5:** keep empty. Those are the board edges.

## Shell (mine)

| Element | Cells |
|---|---|
| Floor y=0 | Border (x≤1, x≥26, z≤1, z≥20): smooth quartz. Kitchen z 2..9: polished tuff. Divider z=10: smooth quartz. Dining z 11..19: polished blackstone. Door threshold (26..27, 0, 5..6): stone bricks |
| Rugs y=1 | Blue carpet runner on the centre aisle x 13..14, z 15..20 |
| Back wall z=0 y 1..8 | White concrete. Budget backing x 2..10, y 2..6: blue concrete. Bill backing x 16..26, y 2..6: black concrete. Dark oak log sill at y=1 and header at y=7 over both boards. Dark oak log column at (26, 1..7, 0) |
| Board lips z=1 | Dark oak bottom slab at y=1 (x 2..10 and 16..25). Closed top dark oak trapdoor at y=6 (same x) |
| Pillars | Quartz pillars with chiseled quartz base (y=1) and capital (y=6), x/z = (1,1) (11,1) (15,1) / side (1,11) (1,17) (1,21) (26,11) (26,17) (26,21) |
| Side walls x=0 / x=27, y 1..8 | White concrete. Windows at z 12..16, y 3..5 (glass panes, stripped spruce mullion at z=14, header log at y=6) |
| Wainscot | x=1 and x=26, y 1..2, stripped spruce wood, spruce trapdoor cap at y=3. West: z 12..16. East: z 12..16 and 18..20 |
| Ceiling | y=7 stripped spruce beams. Along x at z = 1, 5, 9, 13, 17, 21. Along z at x = 1, 5, 9, 13, 14, 18, 22, 26. Coffers are open at y=7. y=8 is white terracotta panels, with a lit waxed copper bulb at each coffer centre, x ∈ {3, 7, 11, 16, 20, 24}, z ∈ {3, 7, 11, 15, 19} |
| Counter base y=1 | x 2..14. z=3 smooth stone, z=4 polished andesite. Stove at z=4: smoker x=12, furnace x=13, smoker x=14, all lit and facing south |
| The pass | z=8, x 2..15, y=1: smooth quartz (top surface y=2.0), with barrels facing south at x=4 and 13. Spruce fence posts at (2, 2..3, 8) and (15, 2..3, 8). Chain rail at y=3, x 3..14. `ck_screen` = (9,2,8) on the pass top, kept clear |
| Delivery door x=27 | Spruce double door at z 5..6, y 1..2. Stripped dark oak frame at z=4 and z=7 (y 1..3), lintel at y=3. Lantern at (26,4,5) on a chain to the beam. Barrels at (26,1,8), (26,2,8), (26,1,9) |
| Tables (unchanged) | A x 4..7, z 12..13. B x 12..15, z 12..13. C x 20..23, z 12..13. D x 8..11, z 16..17. E x 16..19, z 16..17. Dark oak top slab at y=1 plus white carpet at y=2. `SetBuilder.TABLE_TOP_Y = 2.0625` |
| Seats | Spruce stairs at `CrewkitAnchors.SEATS` (y=1), with an open spruce trapdoor backrest at y=2 on the side away from the table |
| Banners | Blue wall banners at (1,6,19) and (26,6,19) |

## Left for KitchenDecor (empty in the shell)

- **Hood, flue, backsplash and under-lighting:** x 12..14, z 1..2, y 1..6, plus z 3..4 at y 2..6. Mind the lit stove at z=4, y=1.
- **Counter tops x 2..11, z 3..4, y=2:** low props only (see the sightline rule).
- **West kitchen wall x=1, z 2..9, y 1..5:** pantry, sink and shelves. x=2, z 5..7 is the chef path, so keep it clear.
- **East pantry x=26, z 2..3, y 1..3.** East back-of-house x 16..25, z 2..4: y=1 only.
- **Table dressing:** you may replace the carpet in the middle cells of each table (A: x 5..6, B: x 13..14, C: x 21..22, D: x 9..10, E: x 17..18; both z rows) with candles or flower pots at y=2. The slab top under them is at y=2.0. Seat-end cells keep their carpet for plates.
- **West sideboard:** x=1, z 18..20, y 1..2. Nothing at y≥3 there, because the banner is at y=6.
- **Walking paths. Keep clear at y 1..2:** chef path x 2..15, z 5..7. Corridor x 2..25, z 9..10 (z=9, x 2..15 holds the thin spruce frontage panels of the pass, so treat it as occupied). Aisles x 9..10 and x 17..18 at z 11..14. Centre aisle x 12..15, z 15..20. Bag drop x 24..25, z 5..6.

## Left for Exterior

Everything outside the walls: x -6..-1 and x 28..33 (west and east), z -6..-1 (north), y -1..14. The windows look west and east at z 12..16, y 3..5. The delivery door opens east at z 5..6.
