package life;

public interface LifecycleListener {
    /**
     * 启动指定的事件
     * @param event 指定的事件
     */
    public void lifecycleEvent(LifecycleEvent event);
}
