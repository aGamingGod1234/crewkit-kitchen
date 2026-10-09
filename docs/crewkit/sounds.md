# Sound picks (one sound per motion)

All vanilla sound events. Play them with `/playsound <id> master @a <x y z> <volume> <pitch>`, or from the mod with the matching `SoundEvents` constant. Test each one at 3:00 on the laptop speakers and the venue audio.

| Kitchen motion | Bridge event | Sound id | Vol | Pitch | Why |
|---|---|---|---|---|---|
| Ticket slides onto the rail | `brief` | `item.book.page_turn` | 1.0 | 1.0 | Paper landing |
| Guests sit down | `brief` | `block.wool.place` | 0.6 | 1.2 | Soft, one per guest, staggered 3 ticks |
| Timer starts | `brief` | `block.note_block.hat` | 0.8 | 1.5 | Tick |
| Item pops onto the head stack | `item_added` | `entity.item.pickup` | 0.8 | 1.0 + 0.05 per item | Rising pitch as the stack grows |
| Reap call counter +1 | `item_added` | `ui.button.click` | 0.4 | 1.6 | Quiet click |
| Budget counter rolls down | `quote` | `block.note_block.hat` | 0.3 | 1.8 | Short ticks while easing (max 8) |
| Over budget: red ticket | `gate_blocked` | `entity.villager.no` | 1.0 | 1.0 | Instantly readable "no" |
| Gate bars drop | `gate_blocked` | `block.iron_door.close` | 1.0 | 0.8 | Heavy |
| Item tumbles off the stack | `item_removed` | `entity.item.pickup` | 0.7 | 0.6 | Same family as adding, lower |
| Bars lift, ticket green | `gate_passed` | `block.iron_door.open` + `block.note_block.chime` | 1.0 | 1.0 / 1.4 | Relief |
| QR appears on the pass | `checkout` | `block.amethyst_block.chime` | 1.0 | 1.0 | Draws eyes to the pass |
| Processing hourglass | `checkout` | `block.note_block.hat` | 0.25 | 1.0 | Slow tick every 20 ticks |
| Delivery bag at the door | `completed` | `item.bundle.drop_contents` + `block.wooden_door.open` | 1.0 | 1.0 | The arrival |
| Order up (bell) | `completed` | `block.note_block.bell` | 1.0 | 1.0 | Kitchen bell |
| Item placed on a plate | `completed` | `item.bundle.remove_one` | 0.8 | 1.1 | One per plate |
| Plate served to a guest | `completed` | `entity.item_frame.add_item` | 0.8 | 1.0 | Small "set down" |
| Bill board stamps the row | `record` | `ui.cartography_table.take_result` | 1.0 | 1.0 | Stamp |
| Celebration (COMPLETED only) | `completed` | `ui.toast.challenge_complete` | 0.7 | 1.0 | Played once, at the end |
| Checkout failed or expired | `failed` / `expired` | `block.beacon.deactivate` | 0.8 | 1.0 | Clear "stopped" |
