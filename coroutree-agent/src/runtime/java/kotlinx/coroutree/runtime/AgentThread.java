package kotlinx.coroutree.runtime;

/** A thread of the agent itself. Hooks recognise it by type and stay silent: the observer is not part of the picture. */
class AgentThread extends Thread {
    AgentThread(String name) {
        super(null, null, name);
        setDaemon(true);
    }
}
