package dev.agaminggod.arenaagents.client.camera;

import dev.agaminggod.arenaagents.camera.CameraRig;
import dev.agaminggod.arenaagents.client.gui.widget.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** On-camera controls keep recording separate from positioning, over the live world. */
public final class CameraRigScreen extends Screen {
    private static final Map<UUID, String> SHOTS = new LinkedHashMap<>();
    private final CameraRig camera;
    private boolean positioning;
    private String selectedShot = "", shot, feedback = "Record a camera path, then replay the shot.";
    private ConsoleButton record, save, preview;
    private ConsoleDropdown<String> saved;
    private int left, top, panelWidth;
    public CameraRigScreen(CameraRig camera) {
        super(Component.literal("Tripod Camera")); this.camera = camera;
        shot = CameraDirectorClient.isRecording() ? CameraDirectorClient.recordingName() : SHOTS.getOrDefault(camera.getUUID(), "shot-" + (CameraDirectorClient.pathNames().size() + 1));
    }
    @Override protected void init() {
        panelWidth = Math.min(270, width - 20); left = width - panelWidth - 10; top = Math.max(8, (height - 210) / 2);
        record = save = preview = null; saved = null;
        int half = (panelWidth - 28) / 2, x = left + 10, y = top + 28;
        addRenderableWidget(new ConsoleButton(font, x, y, half, 20, Component.literal("Record"), !positioning, 0xFFE6B95C, () -> { positioning = false; rebuildWidgets(); }));
        addRenderableWidget(new ConsoleButton(font, x + half + 8, y, half, 20, Component.literal("Position"), positioning, 0xFFE6B95C, () -> { positioning = true; rebuildWidgets(); }));
        y += 28;
        if (!positioning) {
            var name = addRenderableWidget(new ConsoleEditBox(font, x, y, panelWidth - 20, 22, Component.literal("Shot name"), Component.literal("Shot name"), "camera-shot"));
            name.setMaxLength(64); name.setValue(shot); name.setResponder(value -> { shot = value; remember(); });
            y += 28;
            record = button("Record shot", x, y, half, () -> {
                CameraDirectorClient.recordCamera(camera, shot);
                minecraft.setScreen(this);
                if (CameraDirectorClient.isRecording()) feedback = "Recording. Position controls move the camera.";
            });
            save = button("Stop and save", x + half + 8, y, half, () -> { CameraDirectorClient.rigCommand(camera, "stop", 0); CameraDirectorClient.stopRecordingFromGui(); rebuildWidgets(); });
            y += 28;
            button("Viewfinder (K returns)", x, y, panelWidth - 20, () -> CameraDirectorClient.viewCamera(camera));
            y += 28;
            var paths = CameraDirectorClient.pathNames();
            if (!paths.isEmpty()) {
                selectedShot = paths.contains(selectedShot) ? selectedShot : paths.getLast();
                saved = addRenderableWidget(new ConsoleDropdown<>(font, x, y, panelWidth - 20, 20, "Saved shots", paths, selectedShot, value -> value, value -> selectedShot = value, width, height));
                y += 26;
                preview = button("Play saved shot", x, y, panelWidth - 20, () -> { CameraDirectorClient.rigCommand(camera, "stop", 0); CameraDirectorClient.playFromGui(selectedShot, false); });
            }
        } else {
            adjust("Lower", "Raise", x, y, half, "height", -0.1f, 0.1f); y += 23;
            adjust("Pan left", "Pan right", x, y, half, "pan", -10, 10); y += 23;
            adjust("Tilt up", "Tilt down", x, y, half, "tilt", -5, 5); y += 23;
            adjust("Dolly left", "Dolly right", x, y, half, "drive", -90, 90); y += 23;
            adjust("Dolly back", "Dolly forward", x, y, half, "drive", 180, 0); y += 23;
            button("Brake", x, y, panelWidth - 20, () -> CameraDirectorClient.rigCommand(camera, "stop", 0));
        }
        button("Close camera", x, height - 28, panelWidth - 20, this::onClose);
        tick();
    }
    private void remember() { SHOTS.put(camera.getUUID(), shot); if (SHOTS.size() > 64) SHOTS.remove(SHOTS.keySet().iterator().next()); }
    private void adjust(String a, String b, int x, int y, int half, String operation, float av, float bv) {
        button(a, x, y, half, () -> CameraDirectorClient.rigCommand(camera, operation, av));
        button(b, x + half + 8, y, half, () -> CameraDirectorClient.rigCommand(camera, operation, bv));
    }
    private ConsoleButton button(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(new ConsoleButton(font, x, y, w, 20, Component.literal(label), false, 0xFFE6B95C, label.equals("Record shot") ? ConsoleButton.Tone.PRIMARY : ConsoleButton.Tone.SECONDARY, action));
    }
    public void feedback(String message) { feedback = message; }
    @Override public void tick() {
        if (camera.isRemoved() || minecraft.player == null || camera.level() != minecraft.level) { onClose(); return; }
        if (record != null) record.active = !CameraDirectorClient.isRecording() && !shot.isBlank();
        if (save != null) save.active = CameraDirectorClient.isRecording();
        if (preview != null) preview.active = !CameraDirectorClient.isRecording();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        CameraDirectorClient.rigCommand(camera, "stop", 0);
        if (CameraDirectorClient.isRecording()) { CameraDirectorClient.stopRecordingFromGui(); if (CameraDirectorClient.isRecording()) return; }
        if (CameraDirectorClient.isDollyViewfinderActive()) CameraDirectorClient.stopPlaybackFromGui();
        super.onClose();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(left, top, left + panelWidth, height - 4, 0xED14202C);
        graphics.text(font, CameraDirectorClient.isRecording() ? "REC  Tripod Camera" : "Tripod Camera", left + 10, top + 10, CameraDirectorClient.isRecording() ? 0xFFFF7C7C : 0xFFFFFFFF, false);
        String status = positioning ? String.format(Locale.ROOT, "Height %.1fm  Pan %.0f  Tilt %.0f", camera.lensHeight(), camera.getYRot(), camera.getXRot()) : feedback;
        var lines = font.split(Component.literal(status), Math.max(10, left - 20));
        for (int i = 0; i < Math.min(4, lines.size()); i++) graphics.text(font, lines.get(i), 10, height - 56 + i * 11, 0xFFFFFFFF, false);
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        if (saved != null) saved.renderPopup(graphics, mouseX, mouseY);
    }
}
