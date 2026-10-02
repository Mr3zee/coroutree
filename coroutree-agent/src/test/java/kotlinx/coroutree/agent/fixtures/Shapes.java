package kotlinx.coroutree.agent.fixtures;

/** Methods without a body, and overloads: what a version of a library may turn a hooked method into. */
public abstract class Shapes {
    public abstract void later(Object value);

    public native void elsewhere(Object value);

    public String overloaded(Object value) {
        return "object";
    }

    public String overloaded(String value) {
        return "string";
    }
}
