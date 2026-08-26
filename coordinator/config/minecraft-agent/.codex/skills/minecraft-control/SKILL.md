---
name: minecraft-control
description: Use when controlling the embodied Minecraft player through native executor tools.
---

# Minecraft executor tool calls

## Output contract

Tell the executor what to do by emitting a native tool call. Plain assistant text does nothing in Minecraft. A turn may contain several tool calls. The executor runs them one at a time and returns a structured result after each call. Read that result before emitting a later call whose arguments depend on it.

Use this loop:

1. Read the newest event and the last tool result.
2. If a required coordinate, UUID, inventory fact, or world fact is missing or stale, call observe where needed.
3. Emit the smallest useful speech or physical call using exact observed facts.
4. Read the result. Continue the active goal, recover, or finish with evidence.

Formatting, Good, and Bad blocks each show one direct native tool call. Only an explicitly combined turn uses a calls array. Formatting blocks show every accepted field, and all numeric ranges are inclusive. Required fields must be present. Optional fields may be omitted; never copy placeholder text into a call. Do not send unknown fields or arithmetic expressions. Copy coordinates, UUIDs, item IDs, recipe IDs, menu IDs, slots, selectors, and block faces from current observations or tool results.

# Top-level tools

## observe - Refresh the latest compact player, inventory, nearby block, entity, goal, and conversation facts.

Formatting:

```text executor-format
{"tool":"observe","arguments":{}}
```

Good:

```json executor-call
{"tool":"observe","arguments":{}}
```

Bad:

```json executor-bad-call
{"tool":"observe","arguments":{"radius":10}}
```

## moveTo - Navigate the player to an observed position and return the body result.

Formatting:

```text executor-format
{"tool":"moveTo","arguments":{"x":<required finite number -30000000..30000000>,"y":<required finite number -2048..2048>,"z":<required finite number -30000000..30000000>,"tolerance":<optional finite number 0.01..16; default 1>,"sprint":<optional true|false; default true>,"timeoutMs":<optional integer 1..120000; default 30000>}}
```

Good:

```json executor-call
{"tool":"moveTo","arguments":{"x":12,"y":64,"z":12,"tolerance":1,"sprint":true,"timeoutMs":30000}}
```

Bad:

```json executor-bad-call
{"tool":"moveTo","arguments":{"x":"12-1","y":"64+10","z":5}}
```

## mine - Break one observed block and return the body result. Success proves the block broke, not that its drop was collected.

Formatting:

```text executor-format
{"tool":"mine","arguments":{"x":<required integer -30000000..30000000>,"y":<required integer -2048..2048>,"z":<required integer -30000000..30000000>,"timeoutMs":<optional integer 1..120000; default 15000>}}
```

Good:

```json executor-call
{"tool":"mine","arguments":{"x":11,"y":64,"z":10,"timeoutMs":15000}}
```

Bad:

```json executor-bad-call
{"tool":"mine","arguments":{"x":11.5,"y":64,"z":10}}
```

## say - Send public chat, a direct message, or proximity voice. Proximity playback is asynchronous.

Formatting:

```text executor-format
{"tool":"say","arguments":{"message":<required nonblank string 1..256 characters>,"audience":<optional "public"|"direct"|"proximity"; default public, or direct when recipientId is present>,"recipientId":<optional nonblank string 1..256 characters; required only for direct and forbidden otherwise>}}
```

Good:

```json executor-call
{"tool":"say","arguments":{"message":"I am getting wood now.","audience":"proximity"}}
```

Bad:

```json executor-bad-call
{"tool":"say","arguments":{"message":"Come here.","audience":"direct"}}
```

## wait - Pause only when time passing is itself required, then return the body result.

Formatting:

```text executor-format
{"tool":"wait","arguments":{"durationMs":<required integer 1..600000>}}
```

Good:

```json executor-call
{"tool":"wait","arguments":{"durationMs":500}}
```

Bad:

```json executor-bad-call
{"tool":"wait","arguments":{"durationMs":0}}
```

## act - Execute one advanced action. Use one exact actionType and its exact arguments object from the reference below.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":<required supported action name>,"arguments":<required exact object for that actionType>}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"look_at","arguments":{"x":12,"y":65,"z":12}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"fly","arguments":{}}}
```

## sequence - Execute 2 to 8 already-known actions in order, stopping on the first factual failure. Entries are actions, not nested top-level calls.

Formatting:

```text executor-format
{"tool":"sequence","arguments":{"actions":[<2..8 entries shaped as {"actionType":<supported action name>,"arguments":<exact action arguments>}>]}}
```

Good:

```json executor-call
{"tool":"sequence","arguments":{"actions":[{"actionType":"navigate_to","arguments":{"x":11,"y":64,"z":10,"tolerance":1,"sprint":true,"timeoutMs":30000}},{"actionType":"break_block","arguments":{"x":11,"y":64,"z":10,"timeoutMs":15000}}]}}
```

Bad:

```json executor-bad-call
{"tool":"sequence","arguments":{"actions":[{"actionType":"wait","arguments":{"durationMs":500}}]}}
```

## finish - Request Minecraft to verify whether the whole goal is complete

Formatting:

```text executor-format
{"tool":"finish","arguments":{"summary":<required nonblank string 1..512 characters>}}
```

Good:

```json executor-call
{"tool":"finish","arguments":{"summary":"Crafted and collected the iron pickaxe."}}
```

Bad:

```json executor-bad-call
{"tool":"finish","arguments":{"summary":"Done.","completionContract":{"predicates":[{"type":"inventory_min","itemId":"minecraft:iron_pickaxe","count":1}]}}}
```

## Combined speech and action - Acknowledge a physical task and start it in the same turn

Good:

```json executor-calls
{"calls":[{"tool":"say","arguments":{"message":"I am getting wood now.","audience":"proximity"}},{"tool":"mine","arguments":{"x":11,"y":64,"z":10,"timeoutMs":15000}}]}
```

# Advanced act actions

Every action below uses the top-level act wrapper. The inner arguments object must contain exactly the listed fields. These action types may also be used inside sequence without the outer tool wrapper.

## act / move_to - Move directly to a position and return the body result.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"move_to","arguments":{"x":<required finite number>,"y":<required finite number>,"z":<required finite number>,"tolerance":<required finite number 0.01..16>,"sprint":<required true|false>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"move_to","arguments":{"x":12,"y":64,"z":12,"tolerance":1,"sprint":true}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"move_to","arguments":{"x":12,"y":64,"z":12,"tolerance":1}}}
```

## act / navigate_to - Pathfind to a position and return the body result.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"navigate_to","arguments":{"x":<required finite number>,"y":<required finite number>,"z":<required finite number>,"tolerance":<required finite number 0.01..16>,"sprint":<required true|false>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"navigate_to","arguments":{"x":12,"y":64,"z":12,"tolerance":1,"sprint":true,"timeoutMs":30000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"navigate_to","arguments":{"x":12,"y":64,"z":12,"tolerance":1,"sprint":true,"timeoutMs":0}}}
```

## act / look_at - Turn the player view toward a position.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"look_at","arguments":{"x":<required finite number>,"y":<required finite number>,"z":<required finite number>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"look_at","arguments":{"x":12,"y":65,"z":12}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"look_at","arguments":{"x":"tree","y":65,"z":12}}}
```

## act / attack - Attack one observed entity until the action returns.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"attack","arguments":{"targetId":<required canonical UUID>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"attack","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000","timeoutMs":15000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"attack","arguments":{"targetId":"nearest_zombie","timeoutMs":15000}}}
```

## act / fight_target - Approach and fight a target selector until the composite action returns.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"fight_target","arguments":{"targetSelector":<required nonblank string 1..256 characters>,"desiredRange":<required finite number 1..6>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"fight_target","arguments":{"targetSelector":"550e8400-e29b-41d4-a716-446655440000","desiredRange":2,"timeoutMs":30000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"fight_target","arguments":{"targetSelector":"550e8400-e29b-41d4-a716-446655440000","desiredRange":7,"timeoutMs":30000}}}
```

## act / flee_from - Move away from an observed target until the requested distance is reached or the action returns.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"flee_from","arguments":{"targetSelector":<required nonblank string 1..256 characters>,"distance":<required finite number 1..64>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"flee_from","arguments":{"targetSelector":"550e8400-e29b-41d4-a716-446655440000","distance":12,"timeoutMs":30000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"flee_from","arguments":{"targetSelector":"550e8400-e29b-41d4-a716-446655440000","distance":0,"timeoutMs":30000}}}
```

## act / follow_entity - Follow an observed target at the requested distance until the action returns.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"follow_entity","arguments":{"targetSelector":<required nonblank string 1..256 characters>,"distance":<required finite number 1..64>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"follow_entity","arguments":{"targetSelector":"550e8400-e29b-41d4-a716-446655440000","distance":3,"timeoutMs":30000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"follow_entity","arguments":{"targetSelector":"550e8400-e29b-41d4-a716-446655440000","distance":3,"timeoutMs":0}}}
```

## act / select_item - Select an inventory item by its exact observed item ID.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"select_item","arguments":{"itemId":<required nonblank string 1..256 characters>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"select_item","arguments":{"itemId":"minecraft:oak_log"}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"select_item","arguments":{"itemId":""}}}
```

## act / select_tool - Move an observed inventory tool into a hotbar slot and select it.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"select_tool","arguments":{"sourceSlot":<required integer 0..35>,"hotbarSlot":<required integer 0..8>,"expectedItemId":<required nonblank string 1..256 characters>,"minRemainingDurability":<required integer 0..2147483647>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"select_tool","arguments":{"sourceSlot":9,"hotbarSlot":0,"expectedItemId":"minecraft:iron_pickaxe","minRemainingDurability":1}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"select_tool","arguments":{"sourceSlot":36,"hotbarSlot":0,"expectedItemId":"minecraft:iron_pickaxe","minRemainingDurability":1}}}
```

## act / equip_item - Equip an observed inventory item in an armor or offhand slot.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"equip_item","arguments":{"sourceSlot":<required integer 0..35>,"targetSlot":<required "head"|"chest"|"legs"|"feet"|"offhand">,"expectedItemId":<required nonblank string 1..256 characters>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"equip_item","arguments":{"sourceSlot":9,"targetSlot":"head","expectedItemId":"minecraft:iron_helmet"}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"equip_item","arguments":{"sourceSlot":9,"targetSlot":"hand","expectedItemId":"minecraft:iron_helmet"}}}
```

## act / use_item - Use the currently selected item for the requested duration.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"use_item","arguments":{"durationMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"use_item","arguments":{"durationMs":1000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"use_item","arguments":{"durationMs":0}}}
```

## act / use_ranged - Aim and use a ranged item against an observed entity.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"use_ranged","arguments":{"targetId":<required canonical UUID>,"drawDurationMs":<required integer 1..600000>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"use_ranged","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000","drawDurationMs":1000,"timeoutMs":15000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"use_ranged","arguments":{"targetId":"skeleton","drawDurationMs":1000,"timeoutMs":15000}}}
```

## act / block_with_shield - Raise the selected shield for the requested duration.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"block_with_shield","arguments":{"durationMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"block_with_shield","arguments":{"durationMs":2000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"block_with_shield","arguments":{"durationMs":0}}}
```

## act / break_block - Break one block at integer coordinates and return the body result.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"break_block","arguments":{"x":<required 32-bit integer -2147483648..2147483647>,"y":<required 32-bit integer -2147483648..2147483647>,"z":<required 32-bit integer -2147483648..2147483647>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"break_block","arguments":{"x":11,"y":64,"z":10,"timeoutMs":15000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"break_block","arguments":{"x":11.5,"y":64,"z":10,"timeoutMs":15000}}}
```

## act / pick_up_item - Collect one visible dropped-item entity using its exact observed stable UUID.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"pick_up_item","arguments":{"targetSelector":<required canonical UUID>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"pick_up_item","arguments":{"targetSelector":"550e8400-e29b-41d4-a716-446655440000"}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"pick_up_item","arguments":{"targetSelector":"nearest_item"}}}
```

## act / place_block - Place one exact observed inventory block at integer coordinates.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"place_block","arguments":{"x":<required 32-bit integer>,"y":<required 32-bit integer>,"z":<required 32-bit integer>,"face":<required "down"|"up"|"north"|"south"|"west"|"east">,"itemId":<required nonblank string 1..256 characters>,"desiredState":<optional null or nonblank string 1..512 characters>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"place_block","arguments":{"x":11,"y":64,"z":10,"face":"up","itemId":"minecraft:cobblestone","desiredState":null}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"place_block","arguments":{"x":11,"y":64,"z":10,"face":"top","itemId":"minecraft:cobblestone"}}}
```

## act / build_sequence - Place 1 to 32 already-known blocks as one composite build action.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"build_sequence","arguments":{"placements":[<1..32 objects each with x:<32-bit integer>, y:<32-bit integer>, z:<32-bit integer>, face:<"down"|"up"|"north"|"south"|"west"|"east">, itemId:<nonblank string 1..256 characters>, desiredState:<optional null or nonblank string 1..512 characters>>],"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"build_sequence","arguments":{"placements":[{"x":11,"y":64,"z":10,"face":"up","itemId":"minecraft:cobblestone"}],"timeoutMs":30000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"build_sequence","arguments":{"placements":[],"timeoutMs":30000}}}
```

## act / craft_inventory - Craft an exact recipe that is available in the player inventory.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"craft_inventory","arguments":{"recipeId":<required nonblank string 1..256 characters>,"count":<required integer 1..64>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"craft_inventory","arguments":{"recipeId":"minecraft:oak_planks","count":4,"timeoutMs":15000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"craft_inventory","arguments":{"recipeId":"minecraft:oak_planks","timeoutMs":15000}}}
```

## act / craft_table - Craft an exact recipe at an observed crafting-table coordinate.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"craft_table","arguments":{"recipeId":<required nonblank string 1..256 characters>,"x":<required 32-bit integer>,"y":<required 32-bit integer>,"z":<required 32-bit integer>,"count":<required integer 1..64>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"craft_table","arguments":{"recipeId":"minecraft:iron_pickaxe","x":11,"y":64,"z":10,"count":1,"timeoutMs":30000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"craft_table","arguments":{"recipeId":"minecraft:iron_pickaxe","x":11.5,"y":64,"z":10,"count":1,"timeoutMs":30000}}}
```

## act / furnace_transaction - Insert furnace input or fuel, or take furnace output, at an observed furnace.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"furnace_transaction","arguments":{"x":<required 32-bit integer>,"y":<required 32-bit integer>,"z":<required 32-bit integer>,"operation":<required "insert_input"|"insert_fuel"|"take_output">,"inventorySlot":<required integer 0..2147483647>,"count":<required integer 1..64>,"expectedItemId":<required nonblank string 1..256 characters>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"furnace_transaction","arguments":{"x":11,"y":64,"z":10,"operation":"insert_input","inventorySlot":9,"count":1,"expectedItemId":"minecraft:raw_iron","timeoutMs":15000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"furnace_transaction","arguments":{"x":11,"y":64,"z":10,"operation":"smelt","inventorySlot":9,"count":1,"expectedItemId":"minecraft:raw_iron","timeoutMs":15000}}}
```

## act / transfer_container - Move an exact observed item stack between player inventory and an observed container.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"transfer_container","arguments":{"x":<required 32-bit integer>,"y":<required 32-bit integer>,"z":<required 32-bit integer>,"sourceKind":<required "player"|"container">,"sourceSlot":<required integer 0..2147483647>,"destinationKind":<required "player"|"container">,"destinationSlot":<required integer 0..2147483647>,"count":<required integer 1..64>,"expectedItemId":<required nonblank string 1..256 characters>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"transfer_container","arguments":{"x":11,"y":64,"z":10,"sourceKind":"player","sourceSlot":9,"destinationKind":"container","destinationSlot":0,"count":1,"expectedItemId":"minecraft:iron_ingot","timeoutMs":15000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"transfer_container","arguments":{"x":11,"y":64,"z":10,"sourceKind":"chest","sourceSlot":9,"destinationKind":"player","destinationSlot":0,"count":1,"expectedItemId":"minecraft:iron_ingot","timeoutMs":15000}}}
```

## act / drop_item - Drop an exact count from an observed player inventory slot.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"drop_item","arguments":{"slot":<required integer 0..35>,"count":<required integer 1..64>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"drop_item","arguments":{"slot":9,"count":1}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"drop_item","arguments":{"slot":9,"count":0}}}
```

## act / chat - Send public chat, a direct message, or proximity voice through the advanced action boundary.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"chat","arguments":{"message":<required nonblank string 1..512 code points; maximum 280 for proximity>,"audience":<optional "public"|"direct"|"proximity"; default public>,"recipientId":<optional canonical UUID; required only for direct and forbidden otherwise>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"chat","arguments":{"message":"I found the cave.","audience":"direct","recipientId":"550e8400-e29b-41d4-a716-446655440000"}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"chat","arguments":{"message":"Come here.","audience":"direct"}}}
```

## act / wait - Pause within an advanced action or sequence only when time passing is required.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"wait","arguments":{"durationMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"wait","arguments":{"durationMs":500}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"wait","arguments":{"durationMs":0}}}
```

## act / set_door - Open or close a door at observed integer coordinates.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"set_door","arguments":{"x":<required 32-bit integer>,"y":<required 32-bit integer>,"z":<required 32-bit integer>,"open":<required true|false>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"set_door","arguments":{"x":11,"y":64,"z":10,"open":true}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"set_door","arguments":{"x":11,"y":64,"z":10,"open":"true"}}}
```

## act / interact_block - Interact with an observed block using an exact hand, face, and held item.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"interact_block","arguments":{"x":<required 32-bit integer>,"y":<required 32-bit integer>,"z":<required 32-bit integer>,"face":<required "down"|"up"|"north"|"south"|"west"|"east">,"hand":<required "main"|"off">,"expectedItemId":<required nonblank string 1..256 characters>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"interact_block","arguments":{"x":11,"y":64,"z":10,"face":"up","hand":"main","expectedItemId":"minecraft:bucket"}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"interact_block","arguments":{"x":11,"y":64,"z":10,"face":"up","hand":"right","expectedItemId":"minecraft:bucket"}}}
```

## act / interact_entity - Interact with an observed entity using an exact hand and held item.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"interact_entity","arguments":{"targetId":<required canonical UUID>,"hand":<required "main"|"off">,"expectedItemId":<required nonblank string 1..256 characters>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"interact_entity","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000","hand":"main","expectedItemId":"minecraft:wheat"}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"interact_entity","arguments":{"targetId":"cow","hand":"main","expectedItemId":"minecraft:wheat"}}}
```

## act / menu_transfer - Transfer an exact stack between two observed slots in an open menu.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"menu_transfer","arguments":{"menuId":<required nonblank string 1..256 characters>,"sourceSlot":<required integer 0..255>,"destinationSlot":<required integer 0..255>,"count":<required integer 1..64>,"expectedItemId":<required nonblank string 1..256 characters>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"menu_transfer","arguments":{"menuId":"menu-1","sourceSlot":0,"destinationSlot":1,"count":1,"expectedItemId":"minecraft:iron_ingot","timeoutMs":15000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"menu_transfer","arguments":{"menuId":"menu-1","sourceSlot":256,"destinationSlot":1,"count":1,"expectedItemId":"minecraft:iron_ingot","timeoutMs":15000}}}
```

## act / menu_button - Press one observed button in an open menu.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"menu_button","arguments":{"menuId":<required nonblank string 1..256 characters>,"buttonId":<required integer 0..255>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"menu_button","arguments":{"menuId":"menu-1","buttonId":0,"timeoutMs":15000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"menu_button","arguments":{"menuId":"menu-1","buttonId":256,"timeoutMs":15000}}}
```

## act / anvil_rename - Set the rename text in an observed open anvil menu.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"anvil_rename","arguments":{"menuId":<required nonblank string 1..256 characters>,"name":<required nonblank string 1..50 characters>,"timeoutMs":<required integer 1..600000>}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"anvil_rename","arguments":{"menuId":"menu-1","name":"Miner","timeoutMs":15000}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"anvil_rename","arguments":{"menuId":"menu-1","name":"","timeoutMs":15000}}}
```

## act / dismount - Dismount the current ridden entity.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"dismount","arguments":{}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"dismount","arguments":{}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"dismount","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000"}}}
```

## act / start_fall_flying - Start elytra fall-flying when the current state permits it.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"start_fall_flying","arguments":{}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"start_fall_flying","arguments":{}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"start_fall_flying","arguments":{"enabled":true}}}
```

## act / respawn - Respawn after death when the current state permits it.

Formatting:

```text executor-format
{"tool":"act","arguments":{"actionType":"respawn","arguments":{}}}
```

Good:

```json executor-call
{"tool":"act","arguments":{"actionType":"respawn","arguments":{}}}
```

Bad:

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"respawn","arguments":{"immediate":true}}}
```

# Result handling

- SUCCEEDED confirms only the action named in that result.
- PATH_BLOCKED, ACTION_TIMEOUT, moved or missing targets, death, and reconnects require fresh facts and another useful call.
- After mine or break_block, observe and collect the visible drop before relying on inventory. A successful break does not prove pickup.
- After a failed completion verifier, continue the same active goal.
- Proximity speech is asynchronous. After say or act/chat, issue the first known physical call immediately instead of waiting for playback.
- Use wait only when time passing is required by the world. Do not use it to guess whether a drop was collected or to pause between ordinary progression steps.
