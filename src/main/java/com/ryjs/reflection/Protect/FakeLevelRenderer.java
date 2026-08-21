package com.ryjs.reflection.Protect;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Supplier;

public class FakeLevelRenderer extends LevelRenderer {

    public FakeLevelRenderer(Minecraft p1, EntityRenderDispatcher p2, BlockEntityRenderDispatcher p3, RenderBuffers p4) {
        super(p1, p2, p3, p4);
    }

    @Override
    public void renderLevel(PoseStack p_109600_, float p_109601_, long p_109602_, boolean p_109603_, @NotNull Camera p_109604_, @NotNull GameRenderer p_109605_, @NotNull LightTexture p_109606_, @NotNull Matrix4f p_254120_) {
        Frustum frustum;
        boolean flag;
        RenderSystem.setShaderGameTime(this.level.getGameTime(), p_109601_);
        this.blockEntityRenderDispatcher.prepare(this.level, p_109604_, this.minecraft.hitResult);
        this.entityRenderDispatcher.prepare(this.level, p_109604_, this.minecraft.crosshairPickEntity);
        ProfilerFiller profilerfiller = this.level.getProfiler();
        profilerfiller.popPush("light_update_queue");
        this.level.pollLightUpdates();
        profilerfiller.popPush("light_updates");
        this.level.getChunkSource().getLightEngine().runLightUpdates();
        Vec3 vec3 = p_109604_.getPosition();
        double d0 = vec3.x();
        double d1 = vec3.y();
        double d2 = vec3.z();
        Matrix4f matrix4f = p_109600_.last().pose();
        profilerfiller.popPush("culling");
        boolean bl = flag = this.capturedFrustum != null;
        if (flag) {
            frustum = this.capturedFrustum;
            frustum.prepare(this.frustumPos.x, this.frustumPos.y, this.frustumPos.z);
        } else {
            frustum = this.cullingFrustum;
        }
        this.minecraft.getProfiler().popPush("captureFrustum");
        if (this.captureFrustum) {
            this.captureFrustum(matrix4f, p_254120_, vec3.x, vec3.y, vec3.z, flag ? new Frustum(matrix4f, p_254120_) : frustum);
            this.captureFrustum = false;
        }
        profilerfiller.popPush("clear");
        FogRenderer.setupColor(p_109604_, p_109601_, this.minecraft.level, this.minecraft.options.getEffectiveRenderDistance(), p_109605_.getDarkenWorldAmount(p_109601_));
        FogRenderer.levelFogColor();
        RenderSystem.clear(16640, Minecraft.ON_OSX);
        float f = p_109605_.getRenderDistance();
        boolean flag1 = this.minecraft.level.effects().isFoggyAt(Mth.floor(d0), Mth.floor(d1)) || this.minecraft.gui.getBossOverlay().shouldCreateWorldFog();
        FogRenderer.setupFog(p_109604_, FogRenderer.FogMode.FOG_SKY, f, flag1, p_109601_);
        profilerfiller.popPush("sky");
        RenderSystem.setShader((Supplier<ShaderInstance>) ((Supplier) GameRenderer
                                ::getPositionShader));
        this.renderSky(p_109600_, p_254120_, p_109601_, p_109604_, flag1, () -> FogRenderer.setupFog(p_109604_, FogRenderer.FogMode.FOG_SKY, f, flag1, p_109601_));
        // 删除: ForgeHooksClient.dispatchRenderStage(RenderLevelStageEvent.Stage.AFTER_SKY, this,
        // p_109600_, p_254120_, this.ticks, p_109604_, frustum);
        profilerfiller.popPush("fog");
        FogRenderer.setupFog(p_109604_, FogRenderer.FogMode.FOG_TERRAIN, Math.max((float) f, (float) 32.0f), flag1, p_109601_);
        profilerfiller.popPush("terrain_setup");
        this.setupRender(p_109604_, frustum, flag, this.minecraft.player.isSpectator());
        profilerfiller.popPush("compilechunks");
        this.compileChunks(p_109604_);
        profilerfiller.popPush("terrain");
        this.renderChunkLayer(RenderType.solid(), p_109600_, d0, d1, d2, p_254120_);
        this.minecraft.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).setBlurMipmap(false, this.minecraft.options.mipmapLevels().get() > 0);
        this.renderChunkLayer(RenderType.cutoutMipped(), p_109600_, d0, d1, d2, p_254120_);
        this.minecraft.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).restoreLastBlurMipmap();
        this.renderChunkLayer(RenderType.cutout(), p_109600_, d0, d1, d2, p_254120_);
        if (this.level.effects().constantAmbientLight()) {
            Lighting.setupNetherLevel(p_109600_.last().pose());
        } else {
            Lighting.setupLevel(p_109600_.last().pose());
        }
        profilerfiller.popPush("entities");
        this.renderedEntities = 0;
        this.culledEntities = 0;
        if (this.itemEntityTarget != null) {
            this.itemEntityTarget.clear(Minecraft.ON_OSX);
            this.itemEntityTarget.copyDepthFrom(this.minecraft.getMainRenderTarget());
            this.minecraft.getMainRenderTarget().bindWrite(false);
        }
        if (this.weatherTarget != null) {
            this.weatherTarget.clear(Minecraft.ON_OSX);
        }
        if (this.shouldShowEntityOutlines()) {
            this.entityTarget.clear(Minecraft.ON_OSX);
            this.minecraft.getMainRenderTarget().bindWrite(false);
        }
        boolean flag2 = false;
        MultiBufferSource.BufferSource multibuffersource$buffersource = this.renderBuffers.bufferSource();
        for (Entity entity : this.level.entitiesForRendering()) {
            MultiBufferSource multibuffersource;
            BlockPos blockpos;
            /*if (entity instanceof net.rain.glow.ah.entity.AntiHealthEntity) {
                if (entity.tickCount == 0) {
                    entity.xOld = entity.getX();
                    entity.yOld = entity.getY();
                    entity.zOld = entity.getZ();
                }
                if (this.shouldShowEntityOutlines() && this.minecraft.shouldEntityAppearGlowing(entity)) {
                    flag2 = true;
                    OutlineBufferSource outlinebuffersource = this.renderBuffers.outlineBufferSource();
                    multibuffersource = outlinebuffersource;
                    int i = entity.getTeamColor();
                    outlinebuffersource.setColor(FastColor.ARGB32.red(i), FastColor.ARGB32.green(i), FastColor.ARGB32.blue(i), 255);
                } else {
                    if (this.shouldShowEntityOutlines() && entity.hasCustomOutlineRendering(this.minecraft.player)) {
                        flag2 = true;
                    }
                    multibuffersource = multibuffersource$buffersource;
                }
                this.renderEntity(entity, d0, d1, d2, p_109601_, p_109600_, multibuffersource);
                multibuffersource$buffersource.endLastBatch();
                this.checkPoseStack(p_109600_);
                multibuffersource$buffersource.endBatch(RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS));
                multibuffersource$buffersource.endBatch(RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS));
                multibuffersource$buffersource.endBatch(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
                multibuffersource$buffersource.endBatch(RenderType.entitySmoothCutout(TextureAtlas.LOCATION_BLOCKS));
            }*/
            // 恢复原版实体过滤：不可见/越界/相机自身/本地玩家镜像 不渲染（否则第一人称会看到自己的模型 = 灵魂出窍）
            if ((!this.entityRenderDispatcher.shouldRender(entity, frustum, d0, d1, d2)
                            && !entity.hasIndirectPassenger(this.minecraft.player))
                    || (!this.level.isOutsideBuildHeight((blockpos = entity.blockPosition()).getY())
                            && !this.isChunkCompiled(blockpos))
                    || (entity == p_109604_.getEntity() && !p_109604_.isDetached()
                            && (!(p_109604_.getEntity() instanceof LivingEntity)
                                    || !((LivingEntity) p_109604_.getEntity()).isSleeping()))
                    || (entity instanceof LocalPlayer && p_109604_.getEntity() != entity
                            && (entity != this.minecraft.player || this.minecraft.player.isSpectator())))
                continue;
            ++this.renderedEntities;
            if (entity.tickCount == 0) {
                entity.xOld = entity.getX();
                entity.yOld = entity.getY();
                entity.zOld = entity.getZ();
            }
            if (this.shouldShowEntityOutlines() && this.minecraft.shouldEntityAppearGlowing(entity)) {
                flag2 = true;
                OutlineBufferSource outlinebuffersource = this.renderBuffers.outlineBufferSource();
                multibuffersource = outlinebuffersource;
                int i = entity.getTeamColor();
                outlinebuffersource.setColor(FastColor.ARGB32.red(i), FastColor.ARGB32.green(i), FastColor.ARGB32.blue(i), 255);
            } else {
                if (this.shouldShowEntityOutlines() && entity.hasCustomOutlineRendering(this.minecraft.player)) {
                    flag2 = true;
                }
                multibuffersource = multibuffersource$buffersource;
            }
            this.renderEntity(entity, d0, d1, d2, p_109601_, p_109600_, multibuffersource);
        }
        multibuffersource$buffersource.endLastBatch();
        this.checkPoseStack(p_109600_);
        multibuffersource$buffersource.endBatch(RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS));
        multibuffersource$buffersource.endBatch(RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS));
        multibuffersource$buffersource.endBatch(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        multibuffersource$buffersource.endBatch(RenderType.entitySmoothCutout(TextureAtlas.LOCATION_BLOCKS));
        // 删除: ForgeHooksClient.dispatchRenderStage(RenderLevelStageEvent.Stage.AFTER_ENTITIES,
        // this, p_109600_, p_254120_, this.ticks, p_109604_, frustum);
        profilerfiller.popPush("blockentities");
        for (RenderChunkInfo levelrenderer$renderchunkinfo : this.renderChunksInFrustum) {
            List<
                    BlockEntity> list = levelrenderer$renderchunkinfo.chunk.getCompiledChunk().getRenderableBlockEntities();
            if (list.isEmpty()) continue;
            for (BlockEntity blockentity1 : list) {
                int j;
                if (!frustum.isVisible(blockentity1.getRenderBoundingBox())) continue;
                BlockPos blockpos4 = blockentity1.getBlockPos();
                MultiBufferSource multibuffersource1 = multibuffersource$buffersource;
                p_109600_.pushPose();
                p_109600_.translate((double) blockpos4.getX() - d0, (double) blockpos4.getY() - d1, (double) blockpos4.getZ() - d2);
                SortedSet<
                        BlockDestructionProgress> sortedset = this.destructionProgress.get(blockpos4.asLong());
                if (sortedset != null && !sortedset.isEmpty() && (j = sortedset.last().getProgress()) >= 0) {
                    PoseStack.Pose posestack$pose = p_109600_.last();
                    SheetedDecalTextureGenerator vertexconsumer = new SheetedDecalTextureGenerator(this.renderBuffers.crumblingBufferSource().getBuffer((RenderType) ModelBakery.DESTROY_TYPES.get(j)), posestack$pose.pose(), posestack$pose.normal(), 1.0f);
                    multibuffersource1 = p_234298_ -> {
                        VertexConsumer vertexconsumer3 = multibuffersource$buffersource.getBuffer(p_234298_);
                        return p_234298_.affectsCrumbling() ? VertexMultiConsumer.create(vertexconsumer, vertexconsumer3) : vertexconsumer3;
                    };
                }
                if (this.shouldShowEntityOutlines() && blockentity1.hasCustomOutlineRendering(this.minecraft.player)) {
                    flag2 = true;
                }
                this.blockEntityRenderDispatcher.render(blockentity1, p_109601_, p_109600_, multibuffersource1);
                p_109600_.popPose();
            }
        }

        // 修复：移除强制类型转换，直接使用Set迭代
        Set<BlockEntity> globalBlockEntitiesSet = this.globalBlockEntities;
        synchronized (globalBlockEntitiesSet) {
            for (BlockEntity blockentity : globalBlockEntitiesSet) {
                if (!frustum.isVisible(blockentity.getRenderBoundingBox())) continue;
                BlockPos blockpos3 = blockentity.getBlockPos();
                p_109600_.pushPose();
                p_109600_.translate((double) blockpos3.getX() - d0, (double) blockpos3.getY() - d1, (double) blockpos3.getZ() - d2);
                if (this.shouldShowEntityOutlines() && blockentity.hasCustomOutlineRendering(this.minecraft.player)) {
                    flag2 = true;
                }
                this.blockEntityRenderDispatcher.render(blockentity, p_109601_, p_109600_, multibuffersource$buffersource);
                p_109600_.popPose();
            }
        }

        this.checkPoseStack(p_109600_);
        multibuffersource$buffersource.endBatch(RenderType.solid());
        multibuffersource$buffersource.endBatch(RenderType.endPortal());
        multibuffersource$buffersource.endBatch(RenderType.endGateway());
        multibuffersource$buffersource.endBatch(Sheets.solidBlockSheet());
        multibuffersource$buffersource.endBatch(Sheets.cutoutBlockSheet());
        multibuffersource$buffersource.endBatch(Sheets.bedSheet());
        multibuffersource$buffersource.endBatch(Sheets.shulkerBoxSheet());
        multibuffersource$buffersource.endBatch(Sheets.signSheet());
        multibuffersource$buffersource.endBatch(Sheets.hangingSignSheet());
        multibuffersource$buffersource.endBatch(Sheets.chestSheet());
        this.renderBuffers.outlineBufferSource().endOutlineBatch();
        if (flag2) {
            this.entityEffect.process(p_109601_);
            this.minecraft.getMainRenderTarget().bindWrite(false);
        }
        // 删除:
        // ForgeHooksClient.dispatchRenderStage(RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES,
        // this, p_109600_, p_254120_, this.ticks, p_109604_, frustum);
        profilerfiller.popPush("destroyProgress");
        for (Long2ObjectMap.Entry<
                SortedSet<
                        BlockDestructionProgress>> entry : this.destructionProgress.long2ObjectEntrySet()) {
            SortedSet<BlockDestructionProgress> sortedset1;
            double d5;
            double d4;
            BlockPos blockpos2 = BlockPos.of(entry.getLongKey());
            double d3 = (double) blockpos2.getX() - d0;
            if (d3 * d3 + (d4 = (double) blockpos2.getY() - d1) * d4 + (d5 = (double) blockpos2.getZ() - d2) * d5 > 1024.0 || (sortedset1 = entry.getValue()) == null || sortedset1.isEmpty())
                continue;
            int k = sortedset1.last().getProgress();
            p_109600_.pushPose();
            p_109600_.translate((double) blockpos2.getX() - d0, (double) blockpos2.getY() - d1, (double) blockpos2.getZ() - d2);
            PoseStack.Pose posestack$pose1 = p_109600_.last();
            SheetedDecalTextureGenerator vertexconsumer1 = new SheetedDecalTextureGenerator(this.renderBuffers.crumblingBufferSource().getBuffer((RenderType) ModelBakery.DESTROY_TYPES.get(k)), posestack$pose1.pose(), posestack$pose1.normal(), 1.0f);
            ModelData modelData = this.level.getModelDataManager().getAt(blockpos2);
            this.minecraft.getBlockRenderer().renderBreakingTexture(this.level.getBlockState(blockpos2), blockpos2, this.level, p_109600_, vertexconsumer1, modelData == null ? ModelData.EMPTY : modelData);
            p_109600_.popPose();
        }
        this.checkPoseStack(p_109600_);
        HitResult hitresult = this.minecraft.hitResult;
        if (p_109603_ && hitresult != null && hitresult.getType() == HitResult.Type.BLOCK) {
            profilerfiller.popPush("outline");
            BlockPos blockpos1 = ((BlockHitResult) hitresult).getBlockPos();
            BlockState blockstate = this.level.getBlockState(blockpos1);
            if (!ForgeHooksClient.onDrawHighlight(this, p_109604_, hitresult, p_109601_, p_109600_, multibuffersource$buffersource) && !blockstate.isAir() && this.level.getWorldBorder().isWithinBounds(blockpos1)) {
                VertexConsumer vertexconsumer2 = multibuffersource$buffersource.getBuffer(RenderType.lines());
                this.renderHitOutline(p_109600_, vertexconsumer2, p_109604_.getEntity(), d0, d1, d2, blockpos1, blockstate);
            }
        } else if (hitresult != null && hitresult.getType() == HitResult.Type.ENTITY) {
            ForgeHooksClient.onDrawHighlight(this, p_109604_, hitresult, p_109601_, p_109600_, multibuffersource$buffersource);
        }
        this.minecraft.debugRenderer.render(p_109600_, multibuffersource$buffersource, d0, d1, d2);
        multibuffersource$buffersource.endLastBatch();
        PoseStack posestack = RenderSystem.getModelViewStack();
        RenderSystem.applyModelViewMatrix();
        multibuffersource$buffersource.endBatch(Sheets.translucentCullBlockSheet());
        multibuffersource$buffersource.endBatch(Sheets.bannerSheet());
        multibuffersource$buffersource.endBatch(Sheets.shieldSheet());
        multibuffersource$buffersource.endBatch(RenderType.armorGlint());
        multibuffersource$buffersource.endBatch(RenderType.armorEntityGlint());
        multibuffersource$buffersource.endBatch(RenderType.glint());
        multibuffersource$buffersource.endBatch(RenderType.glintDirect());
        multibuffersource$buffersource.endBatch(RenderType.glintTranslucent());
        multibuffersource$buffersource.endBatch(RenderType.entityGlint());
        multibuffersource$buffersource.endBatch(RenderType.entityGlintDirect());
        multibuffersource$buffersource.endBatch(RenderType.waterMask());
        this.renderBuffers.crumblingBufferSource().endBatch();
        if (this.transparencyChain != null) {
            multibuffersource$buffersource.endBatch(RenderType.lines());
            multibuffersource$buffersource.endBatch();
            this.translucentTarget.clear(Minecraft.ON_OSX);
            this.translucentTarget.copyDepthFrom(this.minecraft.getMainRenderTarget());
            profilerfiller.popPush("translucent");
            this.renderChunkLayer(RenderType.translucent(), p_109600_, d0, d1, d2, p_254120_);
            profilerfiller.popPush("string");
            this.renderChunkLayer(RenderType.tripwire(), p_109600_, d0, d1, d2, p_254120_);
            this.particlesTarget.clear(Minecraft.ON_OSX);
            this.particlesTarget.copyDepthFrom(this.minecraft.getMainRenderTarget());
            RenderStateShard.PARTICLES_TARGET.setupRenderState();
            profilerfiller.popPush("particles");
            this.minecraft.particleEngine.render(p_109600_, multibuffersource$buffersource, p_109606_, p_109604_, p_109601_, frustum);
            // 删除: ForgeHooksClient.dispatchRenderStage(RenderLevelStageEvent.Stage.AFTER_PARTICLES,
            // this, p_109600_, p_254120_, this.ticks, p_109604_, frustum);
            RenderStateShard.PARTICLES_TARGET.clearRenderState();
        } else {
            profilerfiller.popPush("translucent");
            if (this.translucentTarget != null) {
                this.translucentTarget.clear(Minecraft.ON_OSX);
            }
            this.renderChunkLayer(RenderType.translucent(), p_109600_, d0, d1, d2, p_254120_);
            multibuffersource$buffersource.endBatch(RenderType.lines());
            multibuffersource$buffersource.endBatch();
            profilerfiller.popPush("string");
            this.renderChunkLayer(RenderType.tripwire(), p_109600_, d0, d1, d2, p_254120_);
            profilerfiller.popPush("particles");
            this.minecraft.particleEngine.render(p_109600_, multibuffersource$buffersource, p_109606_, p_109604_, p_109601_, frustum);
            // 删除: ForgeHooksClient.dispatchRenderStage(RenderLevelStageEvent.Stage.AFTER_PARTICLES,
            // this, p_109600_, p_254120_, this.ticks, p_109604_, frustum);
        }
        posestack.pushPose();
        posestack.mulPoseMatrix(p_109600_.last().pose());
        RenderSystem.applyModelViewMatrix();
        if (this.minecraft.options.getCloudsType() != CloudStatus.OFF) {
            if (this.transparencyChain != null) {
                this.cloudsTarget.clear(Minecraft.ON_OSX);
                RenderStateShard.CLOUDS_TARGET.setupRenderState();
                profilerfiller.popPush("clouds");
                this.renderClouds(p_109600_, p_254120_, p_109601_, d0, d1, d2);
                RenderStateShard.CLOUDS_TARGET.clearRenderState();
            } else {
                profilerfiller.popPush("clouds");
                RenderSystem.setShader((Supplier<ShaderInstance>) ((Supplier) GameRenderer
                                        ::getPositionTexColorNormalShader));
                this.renderClouds(p_109600_, p_254120_, p_109601_, d0, d1, d2);
            }
        }
        if (this.transparencyChain != null) {
            RenderStateShard.WEATHER_TARGET.setupRenderState();
            profilerfiller.popPush("weather");
            this.renderSnowAndRain(p_109606_, p_109601_, d0, d1, d2);
            // 删除: ForgeHooksClient.dispatchRenderStage(RenderLevelStageEvent.Stage.AFTER_WEATHER,
            // this, p_109600_, p_254120_, this.ticks, p_109604_, frustum);
            this.renderWorldBorder(p_109604_);
            RenderStateShard.WEATHER_TARGET.clearRenderState();
            this.transparencyChain.process(p_109601_);
            this.minecraft.getMainRenderTarget().bindWrite(false);
        } else {
            RenderSystem.depthMask(false);
            profilerfiller.popPush("weather");
            this.renderSnowAndRain(p_109606_, p_109601_, d0, d1, d2);
            // 删除: ForgeHooksClient.dispatchRenderStage(RenderLevelStageEvent.Stage.AFTER_WEATHER,
            // this, p_109600_, p_254120_, this.ticks, p_109604_, frustum);
            this.renderWorldBorder(p_109604_);
            RenderSystem.depthMask(true);
        }
        posestack.popPose();
        RenderSystem.applyModelViewMatrix();
        this.renderDebug(p_109600_, multibuffersource$buffersource, p_109604_);
        multibuffersource$buffersource.endLastBatch();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        FogRenderer.setupNoFog();
    }

    @Override
    public void renderClouds(@NotNull PoseStack p_254145_, @NotNull Matrix4f p_254537_, float p_254364_, double p_253843_, double p_253663_, double p_253795_) {
        // 空实现 - 完全禁用云层渲染
    }

    /** 覆写renderSky - 调用renderEndSky并空实现其他部分 */

    /** 覆写renderSnowAndRain - 空实现，不渲染雨雪效果 */
    @Override
    public void renderSnowAndRain(@NotNull LightTexture p_109704_, float p_109705_, double p_109706_, double p_109707_, double p_109708_) {
        // 空实现 - 完全禁用雨雪天气渲染
    }

    /*@Override
    public void renderEntity(Entity p_109518_, double p_109519_, double p_109520_, double p_109521_, float p_109522_, PoseStack p_109523_, MultiBufferSource p_109524_) {
        if (p_109518_ instanceof net.rain.glow.ah.entity.AntiHealthEntity) super.renderEntity(p_109518_, p_109519_, p_109520_, p_109521_, p_109522_, p_109523_, p_109524_);
        super.renderEntity(p_109518_, p_109519_, p_109520_, p_109521_, p_109522_, p_109523_, p_109524_);
    }*/

}
