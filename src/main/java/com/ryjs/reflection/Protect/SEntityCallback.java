package com.ryjs.reflection.Protect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import net.minecraftforge.common.ForgeHooks;

public class SEntityCallback<T extends EntityAccess> implements EntityInLevelCallback {
   public PersistentEntitySectionManager<T> base;
   public T entity;
   public Entity realEntity;
   public long currentSectionKey;
   public EntitySection<T> currentSection;

   public SEntityCallback(PersistentEntitySectionManager<T> base, T p_157614_, long p_157615_, EntitySection<T> p_157616_) {
      this.base = base;
      this.entity = p_157614_;
      this.realEntity = p_157614_ instanceof Entity ? (Entity)p_157614_ : null;
      this.currentSectionKey = p_157615_;
      this.currentSection = p_157616_;
   }

   public void onMove() {
      BlockPos blockpos = this.entity.blockPosition();
      long i = SectionPos.asLong(blockpos);
      if (i != this.currentSectionKey) {
         Visibility visibility = this.currentSection.getStatus();
         if (!this.currentSection.remove(this.entity)) {
            PersistentEntitySectionManager.LOGGER.warn("Entity {} wasn't found in section {} (moving to {})", new Object[]{this.entity, SectionPos.of(this.currentSectionKey), i});
         }

         this.base.removeSectionIfEmpty(this.currentSectionKey, this.currentSection);
         EntitySection<T> entitysection = this.base.sectionStorage.getOrCreateSection(i);
         entitysection.add(this.entity);
         long oldSectionKey = this.currentSectionKey;
         this.currentSection = entitysection;
         this.currentSectionKey = i;
         this.updateStatus(visibility, entitysection.getStatus());
         if (this.realEntity != null) {
            ForgeHooks.onEntityEnterSection(this.realEntity, oldSectionKey, i);
         }
      }

   }

   public void updateStatus(Visibility p_157621_, Visibility p_157622_) {
      Visibility visibility = PersistentEntitySectionManager.getEffectiveStatus(this.entity, p_157621_);
      Visibility visibility1 = PersistentEntitySectionManager.getEffectiveStatus(this.entity, p_157622_);
      if (visibility == visibility1) {
         if (visibility1.isAccessible()) {
            this.base.callbacks.onSectionChange(this.entity);
         }
      } else {
         boolean flag = visibility.isAccessible();
         boolean flag1 = visibility1.isAccessible();
         if (flag && !flag1) {
            this.base.stopTracking(this.entity);
         } else if (!flag && flag1) {
            this.base.startTracking(this.entity);
         }

         boolean flag2 = visibility.isTicking();
         boolean flag3 = visibility1.isTicking();
         if (flag2 && !flag3) {
            this.base.stopTicking(this.entity);
         } else if (!flag2 && flag3) {
            this.base.startTicking(this.entity);
         }

         if (flag1) {
            this.base.callbacks.onSectionChange(this.entity);
         }
      }

   }

   public void onRemove(Entity.RemovalReason p_157619_) {
      if (!this.currentSection.remove(this.entity)) {
         PersistentEntitySectionManager.LOGGER.warn("Entity {} wasn't found in section {} (destroying due to {})", new Object[]{this.entity, SectionPos.of(this.currentSectionKey), p_157619_});
      }

      Visibility visibility = PersistentEntitySectionManager.getEffectiveStatus(this.entity, this.currentSection.getStatus());
      if (visibility.isTicking()) {
         this.base.stopTicking(this.entity);
      }

      if (visibility.isAccessible()) {
         this.base.stopTracking(this.entity);
      }

      if (p_157619_.shouldDestroy()) {
         this.base.callbacks.onDestroyed(this.entity);
      }

      this.base.knownUuids.remove(this.entity.getUUID());
      this.entity.setLevelCallback(NULL);
      this.base.removeSectionIfEmpty(this.currentSectionKey, this.currentSection);
   }
}
