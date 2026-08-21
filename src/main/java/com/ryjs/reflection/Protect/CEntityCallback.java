package com.ryjs.reflection.Protect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import net.minecraftforge.common.ForgeHooks;

public class CEntityCallback<T extends EntityAccess> implements EntityInLevelCallback {
   public TransientEntitySectionManager<T> base;
   public T entity;
   public Entity realEntity;
   public long currentSectionKey;
   public EntitySection<T> currentSection;

   public CEntityCallback(TransientEntitySectionManager<T> base, T p_157673_, long p_157674_, EntitySection<T> p_157675_) {
      this.base = base;
      this.entity = p_157673_;
      this.realEntity = p_157673_ instanceof Entity ? (Entity)p_157673_ : null;
      this.currentSectionKey = p_157674_;
      this.currentSection = p_157675_;
   }

   public void onMove() {
      BlockPos blockpos = this.entity.blockPosition();
      long i = SectionPos.asLong(blockpos);
      if (i != this.currentSectionKey) {
         Visibility visibility = this.currentSection.getStatus();
         if (!this.currentSection.remove(this.entity)) {
            TransientEntitySectionManager.LOGGER.warn("Entity {} wasn't found in section {} (moving to {})", new Object[]{this.entity, SectionPos.of(this.currentSectionKey), i});
         }

         this.base.removeSectionIfEmpty(this.currentSectionKey, this.currentSection);
         EntitySection<T> entitysection = this.base.sectionStorage.getOrCreateSection(i);
         entitysection.add(this.entity);
         long oldSectionKey = this.currentSectionKey;
         this.currentSection = entitysection;
         this.currentSectionKey = i;
         this.base.callbacks.onSectionChange(this.entity);
         if (!this.entity.isAlwaysTicking()) {
            boolean flag = visibility.isTicking();
            boolean flag1 = entitysection.getStatus().isTicking();
            if (flag && !flag1) {
               this.base.callbacks.onTickingEnd(this.entity);
            } else if (!flag && flag1) {
               this.base.callbacks.onTickingStart(this.entity);
            }
         }

         if (this.realEntity != null) {
            ForgeHooks.onEntityEnterSection(this.realEntity, oldSectionKey, i);
         }
      }

   }

   public void onRemove(Entity.RemovalReason p_157678_) {
      if (!this.currentSection.remove(this.entity)) {
         TransientEntitySectionManager.LOGGER.warn("Entity {} wasn't found in section {} (destroying due to {})", new Object[]{this.entity, SectionPos.of(this.currentSectionKey), p_157678_});
      }

      Visibility visibility = this.currentSection.getStatus();
      if (visibility.isTicking() || this.entity.isAlwaysTicking()) {
         this.base.callbacks.onTickingEnd(this.entity);
      }

      this.base.callbacks.onTrackingEnd(this.entity);
      this.base.callbacks.onDestroyed(this.entity);
      this.base.entityStorage.remove(this.entity);
      this.entity.setLevelCallback(NULL);
      this.base.removeSectionIfEmpty(this.currentSectionKey, this.currentSection);
   }
}
