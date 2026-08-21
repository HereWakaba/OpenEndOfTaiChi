package com.ryjs.reflection.hook;

import com.ryjs.hook.hook.InvokeRedirect;
import com.ryjs.reflection.entity.PhantomRegistry;
import com.ryjs.reflection.entity.TaiChiDominion;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelEntityGetter;


public final class PhantomRedirectHooks {

   private PhantomRedirectHooks() {
   }

   /** 首调诊断（每次进程仅打一次，避免刷屏）：确认重定向 handler 确实被调用、且补幻象路径生效。 */
   private static volatile boolean getAllLogged = false;
   private static volatile boolean forEachLogged = false;

   /** getAll 调用点改道：原逻辑结果（含任何过滤）+ 补回幻象（identity 去重，攻击态保留 owner）。 */
   @InvokeRedirect(
      targetClass = "net/minecraft/world/level/entity/LevelEntityGetter",
      method = "getAll",
      mixinExtends = true
   )
   public static Iterable<Entity> redirectGetAll(LevelEntityGetter<Entity> getter) {
      Iterable<Entity> base = getter.getAll();
      List<Entity> phantoms = PhantomRegistry.all();
      if (!getAllLogged) {
         getAllLogged = true;
         int baseHasPhantom = 0;
         int baseMemory = 0;
         // 内存实体识别优先走隐藏核心（验证隐藏实现真实被调用 + MC 依赖解析）；未就绪降级旧路径
         com.ryjs.core.RyjsCore core = com.ryjs.coremod.Agent.AgentUtil.hiddenCore();
         if (base != null) {
            for (Entity e : base) {
               if (PhantomRegistry.contains(e)) {
                  baseHasPhantom++;
               }
               if (core != null ? core.isMemoryEntity(e)
                     : com.ryjs.reflection.entity.EntityReality.isMemoryEntity(e)) {
                  baseMemory++;
               }
            }
         }
         com.ryjs.hook.DiagLog.log("[PhantomRedirect] redirectGetAll 首调: base=" + (base == null ? "null" : count(base))
            + " 幻象数=" + phantoms.size() + " base含幻象=" + baseHasPhantom + " base内存实体=" + baseMemory);
      }
      if (phantoms.isEmpty() && !TaiChiDominion.isAttacking()) {
         return base;
      }
      Set<Entity> merged = Collections.newSetFromMap(new IdentityHashMap<>());
      if (base != null) {
         base.forEach(entity -> {
            if (TaiChiDominion.visibleUnderDominion(entity)) {
               merged.add(entity);
            }
         });
      }
      merged.addAll(phantoms);
      return Collections.unmodifiableSet(merged);
   }

   /**
    * forEach 调用点改道：原逻辑（保留任何过滤语义）+ 补回幻象。
    * 无幻象时零开销透传；有幻象且该 Iterable 元素为实体（实体列表枚举路径）时才补。
    * 参数用 raw 类型（Iterable<?> 的 consumer 捕获无法直接转发，擦除后 desc 一致）。
    */
   @InvokeRedirect(
      targetClass = "java/lang/Iterable",
      method = "forEach",
      mixinExtends = true
   )
   public static void redirectForEach(Iterable iterable, Consumer action) {
      Objects.requireNonNull(action);
      if (PhantomRegistry.isEmpty()) {
         iterable.forEach(action); // 无幻象：原样透传（保留任何调用点级过滤）
         return;
      }
      if (!forEachLogged) {
         forEachLogged = true;
         com.ryjs.hook.DiagLog.log("[PhantomRedirect] redirectForEach 首调: 元素=" + count(iterable) + " 幻象数=" + PhantomRegistry.size());
      }
      iterable.forEach(action); // 原逻辑（保留任何调用点级过滤）
      boolean isEntityIterable = false;
      for (Object t : iterable) {
         if (t instanceof Entity) {
            isEntityIterable = true;
            break;
         }
      }
      if (isEntityIterable) {
         for (Entity inst : PhantomRegistry.all()) {
            action.accept(inst);
         }
      }
   }

   private static int count(Iterable<?> iterable) {
      int n = 0;
      for (Object ignored : iterable) {
         if (++n > 1000) {
            break;
         }
      }
      return n;
   }
}
