package dev.agaminggod.arenaagents.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** Checks the actual Swing-to-render bridge without opening an app or initializing a GPU. */
public final class PlanItemIconsVerification {
 private PlanItemIconsVerification() { }
 public static void main(String[] args) throws Exception { System.out.println("Minecraft plan icon bridge verification passed (" + verify() + " checks); GPU rendering is not exercised."); }
 public static int verify() throws Exception {
  Class<?> icons = Class.forName("dev.agaminggod.arenaagents.client.control.PlanItemIcons");
  Method key = method(icons, "itemId", JsonObject.class);
  int checks = 0;
  checks += expect("minecraft:diamond", key.invoke(null, json("{\"kind\":\"inventory\",\"evidence\":{\"itemIds\":[\"minecraft:diamond\"]}}")));
  checks += expect("minecraft:diamond_pickaxe", key.invoke(null, json("{\"kind\":\"inventory\",\"evidence\":{\"itemIds\":[\"minecraft:diamond_pickaxe\"]}}")));
  checks += expect("minecraft:oak_log", key.invoke(null, json("{\"kind\":\"inventory\",\"evidence\":{\"itemIds\":[\"minecraft:oak_log\",\"minecraft:birch_log\"]}}")));
  checks += expect("minecraft:obsidian", key.invoke(null, json("{\"kind\":\"world\",\"evidence\":{\"blockId\":\"minecraft:obsidian\"}}")));
  checks += expect("example:custom_ore", key.invoke(null, json("{\"kind\":\"world\",\"evidence\":{\"blockId\":\"example:custom_ore\"}}")));
  checks += expect("minecraft:crafting_table", key.invoke(null, json("{\"kind\":\"manual\",\"label\":\"Prepare tools and food\",\"evidence\":null}")));
  checks += expect("minecraft:knowledge_book", key.invoke(null, json("{\"kind\":\"milestone\",\"evidence\":null}")));
  checks += expect("minecraft:map", key.invoke(null, json("{\"kind\":\"world\",\"evidence\":null}")));
  checks += expect("minecraft:bundle", key.invoke(null, json("{\"kind\":\"inventory\",\"evidence\":{\"itemIds\":[\"../../secret\",42,null]}}")));
  // A top-row opaque red pixel and bottom-row translucent blue pixel catch both channel swaps and vertical flips.
  BufferedImage rgba = (BufferedImage) method(icons, "rgbaImage", ByteBuffer.class, int.class, int.class)
   .invoke(null, ByteBuffer.wrap(new byte[]{0,0,(byte)255,64,(byte)255,0,0,(byte)255}), 1, 2);
  checks += expect(0xffff0000, rgba.getRGB(0,0));
  checks += expect(0x400000ff, rgba.getRGB(0,1));

  Method retain = method(icons,"retain",Set.class), image = method(icons,"image",JsonObject.class), clear = method(icons,"clear"), invalidate = method(icons,"invalidate");
  JsonObject diamond = json("{\"kind\":\"inventory\",\"evidence\":{\"itemIds\":[\"minecraft:diamond\"]}}");
  Map<?,?> images = (Map<?,?>) field(icons,"IMAGES");
  Queue<?> queue = (Queue<?>) field(icons,"QUEUE");
  try {
   clear.invoke(null); retain.invoke(null,Set.of("minecraft:diamond"));
   checks += expect(null,image.invoke(null,diamond));
   Object request = queue.element(); image.invoke(null,diamond);
   checks += expect(1,queue.size()); // Repeat Swing repaints share one asynchronous request.
   queue.poll(); // The render hook consumes the queued capture before asynchronous completion.
   Method publish = method(icons,"publish",request.getClass(),BufferedImage.class);
   Method failed = method(icons,"failed",request.getClass());
   Map<?,?> requested = (Map<?,?>) field(icons,"REQUESTED");
   BufferedImage finished = new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
   publish.invoke(null,request,finished);
   checks += expect(finished,image.invoke(null,diamond));
   invalidate.invoke(null); publish.invoke(null,request,finished);
   checks += expect(0,images.size()); // An old GPU callback cannot resurrect a prior resource pack image.
   image.invoke(null,diamond); checks += expect(1,queue.size()); // A resource reload schedules a fresh capture.
   Object reloaded = queue.element(); retain.invoke(null,Set.of("minecraft:diamond_pickaxe")); publish.invoke(null,reloaded,finished);
   checks += expect(0,images.size()); checks += expect(0,queue.size()); // Replaced plans drop old images and pending captures.
   clear.invoke(null); image.invoke(null,diamond); checks += expect(0,queue.size()); // Closing/disconnecting never starts render work.

   retain.invoke(null,Set.of("minecraft:diamond")); image.invoke(null,diamond);
   Object firstAttempt = queue.poll(); failed.invoke(null,firstAttempt);
   checks += expect(0,requested.size()); checks += expect(0,queue.size()); // Failure frees its marker without automatically retrying.
   checks += expect(null,image.invoke(null,diamond));
   Object retry = queue.element();
   if (retry == firstAttempt) throw new AssertionError("Failed capture was not replaced by a fresh request"); checks++;
   image.invoke(null,diamond); checks += expect(1,queue.size()); // Repaints during retry still coalesce.
   failed.invoke(null,firstAttempt); checks += expect(retry,requested.get("minecraft:diamond"));
   queue.poll(); publish.invoke(null,retry,finished);
   checks += expect(finished,image.invoke(null,diamond)); checks += expect(0,requested.size());

   invalidate.invoke(null); image.invoke(null,diamond); Object current = queue.element();
   failed.invoke(null,retry); checks += expect(current,requested.get("minecraft:diamond")); // A prior resource-generation failure cannot release the current capture.
   clear.invoke(null); failed.invoke(null,current); publish.invoke(null,current,finished);
   image.invoke(null,diamond); checks += expect(0,queue.size()); checks += expect(0,images.size());
  } finally { clear.invoke(null); }
  javax.swing.SwingUtilities.invokeAndWait(() -> { });
  return checks;
 }
 private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
 private static Method method(Class<?> type,String name,Class<?>... parameters) throws Exception { var method=type.getDeclaredMethod(name,parameters);method.setAccessible(true);return method; }
 private static Object field(Class<?> type,String name) throws Exception { var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(null); }
 private static int expect(Object expected,Object actual) { if(!java.util.Objects.equals(expected,actual))throw new AssertionError("Expected "+expected+", observed "+actual);return 1; }
}
