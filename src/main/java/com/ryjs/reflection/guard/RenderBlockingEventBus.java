package com.ryjs.reflection.guard;

import net.minecraftforge.eventbus.BusBuilderImpl;
import net.minecraftforge.eventbus.EventBus;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.GenericEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.IEventBusInvokeDispatcher;

import java.util.function.Consumer;


public class RenderBlockingEventBus extends EventBus {

    /** 原总线（转发目标）。 */
    private final IEventBus delegate;

    public RenderBlockingEventBus(BusBuilderImpl builder, IEventBus originalBus) {
        super(builder);
        this.delegate = originalBus;
    }

    @Override
    public boolean post(Event event) {
        // 战斗模式（防御 / MAX）或重绘（实时/全量）：所有 Forge 事件不派发（渲染回调源头掐断）
        if (RenderProtect.isProtectEnabled() || WindowGuard.isRealtimeRedraw() || WindowGuard.isFullRedraw()) {
            return false;
        }
        return delegate.post(event);
    }

    @Override
    public boolean post(Event event, IEventBusInvokeDispatcher wrapper) {
        if (RenderProtect.isProtectEnabled() || WindowGuard.isRealtimeRedraw() || WindowGuard.isFullRedraw()) {
            return false;
        }
        return delegate.post(event, wrapper);
    }

    // ============================ 注册/监听器：转发 delegate ============================

    @Override
    public void register(Object target) {
        delegate.register(target);
    }

    @Override
    public void unregister(Object object) {
        delegate.unregister(object);
    }

    @Override
    public <T extends Event> void addListener(Consumer<T> consumer) {
        delegate.addListener(consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        delegate.addListener(priority, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> consumer) {
        delegate.addListener(priority, receiveCancelled, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(priority, receiveCancelled, eventType, consumer);
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, Consumer<T> consumer) {
        delegate.addGenericListener(genericClassFilter, consumer);
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, EventPriority priority, Consumer<T> consumer) {
        delegate.addGenericListener(genericClassFilter, priority, consumer);
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(
            Class<F> genericClassFilter, EventPriority priority, boolean receiveCancelled, Consumer<T> consumer) {
        delegate.addGenericListener(genericClassFilter, priority, receiveCancelled, consumer);
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(
            Class<F> genericClassFilter, EventPriority priority, boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer) {
        delegate.addGenericListener(genericClassFilter, priority, receiveCancelled, eventType, consumer);
    }

    // ============================ 生命周期：防外部关闭 ============================

    @Override
    public void shutdown() {
    }

    @Override
    public void start() {
    }
}
