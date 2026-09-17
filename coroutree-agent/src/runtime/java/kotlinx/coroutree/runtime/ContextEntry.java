package kotlinx.coroutree.runtime;

/**
 * One coroutine context element, described once and then shared by every node that inherits the element.
 * Keeps the element itself: inheritance is detected by identity, and whoever holds this entry (a job's node)
 * already keeps the element alive through its context.
 */
final class ContextEntry {
    final Object element;
    final int kind;
    final String key;
    final String value;
    final boolean threadContextElement;

    ContextEntry(Object element, int kind, String key, String value, boolean threadContextElement) {
        this.element = element;
        this.kind = kind;
        this.key = key;
        this.value = value;
        this.threadContextElement = threadContextElement;
    }
}
