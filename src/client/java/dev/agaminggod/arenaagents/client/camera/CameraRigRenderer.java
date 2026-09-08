package dev.agaminggod.arenaagents.client.camera;

import dev.agaminggod.arenaagents.camera.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.block.*;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

public final class CameraRigRenderer extends EntityRenderer<CameraRig, CameraRigRenderer.State> {
    private static final BlockDisplayContext DISPLAY = BlockDisplayContext.create();
    private final BlockModelResolver models;
    public CameraRigRenderer(EntityRendererProvider.Context context) { super(context); models = context.getBlockModelResolver(); shadowRadius = 0.5f; }
    public static final class State extends EntityRenderState {
        final BlockModelRenderState base = new BlockModelRenderState(), column = new BlockModelRenderState(), head = new BlockModelRenderState();
        float height, yaw, pitch;
    }
    @Override public State createRenderState() { return new State(); }
    @Override public void extractRenderState(CameraRig rig, State state, float partial) {
        super.extractRenderState(rig, state, partial);
        state.height = rig.lensHeight(); state.yaw = rig.getYRot(); state.pitch = rig.getXRot();
        models.update(state.base, CameraDolly.TRIPOD.defaultBlockState(), DISPLAY);
        models.update(state.column, CameraDolly.COLUMN.defaultBlockState(), DISPLAY);
        models.update(state.head, CameraDolly.HEAD.defaultBlockState(), DISPLAY);
    }
    @Override public void submit(State state, PoseStack pose, SubmitNodeCollector nodes, CameraRenderState camera) {
        pose.pushPose(); pose.translate(-0.5, 0, -0.5);
        state.base.submit(pose, nodes, state.lightCoords, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, state.outlineColor);
        pose.popPose(); pose.pushPose(); pose.translate(-0.5, 0.5, -0.5); pose.scale(1, state.height - 0.5f, 1);
        state.column.submit(pose, nodes, state.lightCoords, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, state.outlineColor);
        pose.popPose(); pose.pushPose(); pose.translate(0, state.height, 0);
        pose.mulPose(Axis.YP.rotationDegrees(180 - state.yaw)); pose.mulPose(Axis.XP.rotationDegrees(-state.pitch)); pose.translate(-0.5, -0.2, -0.5);
        state.head.submit(pose, nodes, state.lightCoords, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, state.outlineColor);
        pose.popPose(); super.submit(state, pose, nodes, camera);
    }
}
