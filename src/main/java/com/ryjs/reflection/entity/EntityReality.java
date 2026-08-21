package com.ryjs.reflection.entity;

import net.minecraft.world.entity.Entity;


public final class EntityReality {

   private EntityReality() {
   }

   public static boolean isMemoryEntity(Entity entity) {
      if (entity == null) {
         return false;
      }
      try {
         if (entity.isAddedToWorld()) {
            return false;
         }
         if (entity.level() == null) {
            return false;
         }
         return true;
      } catch (Throwable t) {
         return false;
      }
   }
}
