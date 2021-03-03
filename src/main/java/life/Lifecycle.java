package life;

public interface Lifecycle {
    /*--------------------------生命周期类型-----------------------*/
    // 初始化之前触发事件
    public static final String BEFORE_INIT_EVENT = "before_init";
    // 初始化之后触发事件
    public static final String AFTER_INIT_EVENT = "after_init";
    // 启动之前触发事件
    public static final String BEFORE_START_EVENT = "before_start";
    // 停止时触发事件
    public static final String START_EVENT = "start";
    // 停止之后触发事件
    public static final String AFTER_START_EVENT = "after_start";
    // 停止之前触发事件
    public static final String BEFORE_STOP_EVENT = "before_stop";
    // 停止时触发事件
    public static final String STOP_EVENT = "stop";
    // 停止之后触发事件
    public static final String AFTER_STOP_EVENT = "after_stop";
    // 销毁之前触发事件
    public static final String BEFORE_DESTROY_EVENT = "before_destroy";
    // 销毁之后触发事件
    public static final String AFTER_DESTROY_EVENT = "after_destroy";
    /*---------------------------监听器相关方法-----------------------*/
    // 添加生命周期事件监听器
    public void addLifecycleListener(LifecycleListener listener);
    // 获取所有生命周期事件监听器
    public LifecycleListener[] findLifecycleListener();
    // 移除响应的生命周期事件监听器
    public void removeLifecycleListener(LifecycleListener listener);
    /*---------------------------生命周期方法--------------------------*/
    // 初始化,为开始事件做准备
    public void init();
    // 启动方法
    public void start();
    // 停止
    public void stop();
    // 销毁
    public void destroy();

    public LifecycleState getState();

}
