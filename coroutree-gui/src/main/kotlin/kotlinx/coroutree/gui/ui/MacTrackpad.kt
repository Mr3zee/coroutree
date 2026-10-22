package kotlinx.coroutree.gui.ui

import java.lang.reflect.Proxy
import javax.swing.JComponent

/**
 * The trackpad pinch on macOS. AWT reports it only through `com.apple.eawt.event`, never as a mouse event, so Compose
 * does not see it. The package exists on macOS alone: the listener is a reflective proxy, and elsewhere nothing is
 * installed.
 */
internal object MacTrackpad {
    private val utilities = loadOrNull("com.apple.eawt.event.GestureUtilities")
    private val gestureListener = loadOrNull("com.apple.eawt.event.GestureListener")
    private val magnificationListener = loadOrNull("com.apple.eawt.event.MagnificationListener")

    /**
     * Calls [onMagnify] with each pinch update over [component] or one of its children. The argument is the
     * fractional change of the gesture, so `1 + magnification` is the zoom factor of the update. Returns the uninstaller.
     */
    fun onMagnify(component: JComponent, onMagnify: (Double) -> Unit): () -> Unit {
        if (utilities == null || gestureListener == null || magnificationListener == null) return {}
        val listener = Proxy.newProxyInstance(magnificationListener.classLoader, arrayOf(magnificationListener)) { proxy, method, args ->
            when (method.name) {
                "magnify" -> {
                    val event = args[0]
                    onMagnify(event.javaClass.getMethod("getMagnification").invoke(event) as Double)
                    null
                }
                // GestureHandler keeps the listener in a list: removing it goes through equals.
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MagnificationListener"
                else -> null
            }
        }
        utilities.getMethod("addGestureListenerTo", JComponent::class.java, gestureListener).invoke(null, component, listener)
        return { utilities.getMethod("removeGestureListenerFrom", JComponent::class.java, gestureListener).invoke(null, component, listener) }
    }

    private fun loadOrNull(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (_: ClassNotFoundException) {
        null
    }
}
