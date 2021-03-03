package life;

import java.util.EventObject;

public final class LifecycleEvent extends EventObject {

    private final Object data;

    private final String type;


    /**
     * Constructs a prototypical Event.
     *
     * @param source The object on which the Event initially occurred.
     * @param data
     * @param type
     * @throws IllegalArgumentException if source is null.
     */
    public LifecycleEvent(Object source, Object data, String type) {
        super(source);
        this.data = data;
        this.type = type;
    }

    public Object getData() {
        return data;
    }

    public String getType() {
        return type;
    }

    public Lifecycle getLifecycle(){
        return (Lifecycle)getSource();
    }
}
