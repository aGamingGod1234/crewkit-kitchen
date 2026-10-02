package dev.agaminggod.arenaagents.client.control;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Copies the game's actual GUI item models into read-only images for the Swing plan. */
public final class PlanItemIcons {
 private static final Logger LOGGER = LoggerFactory.getLogger(PlanItemIcons.class);
 private static final int SIZE = 32;
 private static final Map<String, BufferedImage> IMAGES = new ConcurrentHashMap<>();
 private static final Map<String, Request> REQUESTED = new ConcurrentHashMap<>();
 private static final ConcurrentLinkedQueue<Request> QUEUE = new ConcurrentLinkedQueue<>();
 private static volatile Set<String> wanted = Set.of();
 private static volatile long generation;
 private static long rendererGeneration = -1;
 private static Renderer renderer; // render thread only
 private static boolean registered;

 private PlanItemIcons() { }

 public static synchronized void register() {
  if (registered) return;
  registered = true;
  Identifier id = Identifier.fromNamespaceAndPath("arenaagents", "plan_item_icons");
  ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
  loader.registerReloadListener(id, new SimpleReloadListener<Void>() {
   @Override protected Void prepare(PreparableReloadListener.SharedState state) { return null; }
   @Override protected void apply(Void value, PreparableReloadListener.SharedState state) { invalidate(); }
  });
  loader.addListenerOrdering(ResourceReloaderKeys.AFTER_VANILLA, id);
 }

 /** Stable presentation keys come from evidence. No label parsing or gameplay choices occur. */
 static String itemId(JsonObject step) {
  String kind = string(step, "kind");
  var value = step.get("evidence");
  if (value != null && value.isJsonObject()) {
   var evidence = value.getAsJsonObject();
   if (kind.equals("inventory") && evidence.has("itemIds") && evidence.get("itemIds").isJsonArray()) {
    for (var item : evidence.getAsJsonArray("itemIds"))
     if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() && validId(item.getAsString())) return item.getAsString();
   }
   String block = string(evidence, "blockId");
   if (kind.equals("world") && validId(block)) return block;
  }
  return fallback(kind);
 }

 private static String fallback(String kind) {
  return switch (kind) {
   case "inventory" -> "minecraft:bundle";
   case "world" -> "minecraft:map";
   case "milestone" -> "minecraft:knowledge_book";
   default -> "minecraft:crafting_table";
  };
 }
 private static String string(JsonObject object, String field) {
  var value = object.get(field);
  return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
 }
 private static boolean validId(String value) { return value.length() <= 128 && value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"); }

 static synchronized void retain(Set<String> keys) {
  wanted = Set.copyOf(keys);
  IMAGES.keySet().retainAll(wanted);
  REQUESTED.keySet().retainAll(wanted);
  QUEUE.removeIf(request -> !wanted.contains(request.itemId));
 }

 /** EDT only reads finished images; all Minecraft/GPU work stays on the render thread. */
 static synchronized BufferedImage image(JsonObject step) {
  String key = itemId(step);
  BufferedImage image = IMAGES.get(key);
  if (image == null && wanted.contains(key) && !REQUESTED.containsKey(key)) {
   Request request = new Request(key, fallback(string(step, "kind")), generation);
   REQUESTED.put(key, request); QUEUE.add(request);
  }
  return image;
 }

 public static synchronized void invalidate() {
  generation++;
  IMAGES.clear(); REQUESTED.clear(); QUEUE.clear();
  LiveAgentWindows.iconsChanged();
 }

 static synchronized void clear() { wanted = Set.of(); invalidate(); }

 /** Called after vanilla GameRenderer.render, outside world or HUD submission. */
 public static void renderPending(Minecraft client) {
  RenderSystem.assertOnRenderThread();
  if (rendererGeneration != generation) {
   if (renderer != null) renderer.close();
   renderer = null; rendererGeneration = generation;
  }
  if (client.level == null || !LiveAgentWindows.planEnabled() || client.getOverlay() != null) return;
  // One cache miss per frame avoids a burst of icon work when opening a large plan.
  Request request = QUEUE.poll();
  if (request == null || !activeRequest(request)) return;
  try {
   if (renderer == null) renderer = new Renderer(client);
   renderer.render(client, request);
  } catch (RuntimeException exception) {
   failed(request);
   LOGGER.warn("Could not render a Minecraft plan item icon: {}", request.itemId, exception);
  }
 }

 private static synchronized void publish(Request request, BufferedImage image) {
  if (!activeRequest(request)) return;
  IMAGES.put(request.itemId, image);
  REQUESTED.remove(request.itemId);
  LiveAgentWindows.iconsChanged();
 }

 private static synchronized boolean activeRequest(Request request) {
  // Identity also distinguishes two captures of the same item within one resource generation.
  return request.generation == generation && wanted.contains(request.itemId) && REQUESTED.get(request.itemId) == request;
 }

 private static synchronized void failed(Request request) {
  if (activeRequest(request)) REQUESTED.remove(request.itemId);
  // A later normal repaint may retry. Do not repaint on failure and create a retry loop.
 }

 /** Native atlas readback is bottom-up RGBA, whereas BufferedImage stores top-down ARGB. */
 static BufferedImage rgbaImage(ByteBuffer rgba, int width, int height) {
  BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
  for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
   int offset = ((height - y - 1) * width + x) * 4;
   int color = Byte.toUnsignedInt(rgba.get(offset + 3)) << 24 | Byte.toUnsignedInt(rgba.get(offset)) << 16
    | Byte.toUnsignedInt(rgba.get(offset + 1)) << 8 | Byte.toUnsignedInt(rgba.get(offset + 2));
   image.setRGB(x, y, color);
  }
  return image;
 }

 private record Request(String itemId, String fallback, long generation) { }

 private static final class Renderer implements AutoCloseable {
  private final ByteBufferBuilder vertices = new ByteBufferBuilder(64 * 1024);
  private final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(vertices);
  private final SubmitNodeStorage submits = new SubmitNodeStorage();
  private final FeatureRenderDispatcher features;
  private final GuiItemAtlas atlas;

  Renderer(Minecraft client) {
   // Separate submit storage prevents the icon pass from consuming or drawing game submissions.
   features = new FeatureRenderDispatcher(submits, client.getModelManager(), buffers, client.getAtlasManager(),
    client.renderBuffers().outlineBufferSource(), buffers, client.font, client.gameRenderer.getGameRenderState());
   atlas = new GuiItemAtlas(submits, features, buffers, 256, SIZE);
  }

  void render(Minecraft client, Request request) {
   var id = Identifier.tryParse(request.itemId);
   var item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
   if (item == Items.AIR) item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(request.fallback)).orElse(Items.KNOWLEDGE_BOOK);
   var state = new TrackingItemStackRenderState();
   client.getItemModelResolver().updateForTopItem(state, item.getDefaultInstance(), ItemDisplayContext.GUI, client.level, client.player, 0);
   if (state.isEmpty()) { failed(request); return; }
   var projection = RenderSystem.getProjectionMatrixBuffer();
   var projectionType = RenderSystem.getProjectionType();
   var color = RenderSystem.outputColorTextureOverride;
   var depth = RenderSystem.outputDepthTextureOverride;
   var lights = RenderSystem.getShaderLights();
   var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
   boolean scissorEnabled = scissor.enabled();
   int sx = scissor.x(), sy = scissor.y(), sw = scissor.width(), sh = scissor.height();
   try {
    if (!atlas.tryPrepareFor(Set.of(state.getModelIdentity()))) { failed(request); return; }
    var slot = atlas.getOrUpdate(state);
    var texture = slot.textureView().texture();
    int x = Math.round(slot.u0() * atlas.textureSize());
    int y = Math.round(slot.v1() * atlas.textureSize());
    var device = RenderSystem.getDevice();
    GpuBuffer buffer = device.createBuffer(() -> "Arena plan icon readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, SIZE * SIZE * 4L);
    var commands = device.createCommandEncoder();
    try {
     commands.copyTextureToBuffer(texture, buffer, 0, () -> {
      try (buffer; var mapped = commands.mapBuffer(buffer, true, false)) { publish(request, rgbaImage(mapped.data(), SIZE, SIZE)); }
      catch (RuntimeException exception) { failed(request); LOGGER.warn("Could not read a Minecraft plan item icon: {}", request.itemId, exception); }
     }, 0, x, y, SIZE, SIZE);
    } catch (RuntimeException exception) { buffer.close(); throw exception; }
   } finally {
    features.clearSubmitNodes(); features.endFrame(); atlas.endFrame();
    RenderSystem.outputColorTextureOverride = color; RenderSystem.outputDepthTextureOverride = depth;
    RenderSystem.setProjectionMatrix(projection, projectionType); RenderSystem.setShaderLights(lights);
    if (scissorEnabled) RenderSystem.enableScissorForRenderTypeDraws(sx, sy, sw, sh); else RenderSystem.disableScissorForRenderTypeDraws();
   }
  }
  @Override public void close() { atlas.close(); features.close(); vertices.close(); }
 }
}
