package com.ryjs.reflection.Protect;

import net.minecraft.world.entity.LivingEntity;

import java.util.List;

public class EntityInstance<E extends LivingEntity> {
   public LivingEntity serverInstance;
   public LivingEntity clientInstance;

   public void put(LivingEntity entity) {
      if (!entity.level().isClientSide()) {
         this.serverInstance = this.serverInstance != null ? this.serverInstance : entity;
      } else if (entity.level().isClientSide()) {
         this.clientInstance = this.clientInstance != null ? this.clientInstance : entity;
      }

   }

   public void update(LivingEntity entity) {
      if (!entity.level().isClientSide()) {
         this.serverInstance = entity;
      } else if (entity.level().isClientSide()) {
         this.clientInstance = entity;
      }

   }

   public List<LivingEntity> getEntities() {
      return List.of(this.serverInstance, this.clientInstance);
   }

   public String toString() {
      String var10000 = String.valueOf(this.clientInstance);
      return "Instance[client:" + var10000 + " & server:" + String.valueOf(this.serverInstance) + "]";
   }
}
