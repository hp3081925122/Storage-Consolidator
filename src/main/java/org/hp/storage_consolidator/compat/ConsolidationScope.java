package org.hp.storage_consolidator.compat;

/** 将整理专用行为限制在当前线程的同步调用内。 */
public final class ConsolidationScope implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 支持嵌套调用，关闭时恢复进入前的状态。 */
    public ConsolidationScope() {
        DEPTH.set(DEPTH.get() + 1);
    }

    /** 普通玩家操作和自动化不在此作用域内。 */
    public static boolean active() {
        return DEPTH.get() > 0;
    }

    /** 即使整理抛出异常也必须释放线程状态。 */
    @Override
    public void close() {
        int remaining = DEPTH.get() - 1;
        if (remaining == 0) DEPTH.remove();
        else DEPTH.set(remaining);
    }
}
