package org.zipp.ai.application.turn;

/** Local pause/drain barrier used around migration generation changes. */
public interface AdmissionBarrier {

    void pauseAndDrain();

    void resume();

    boolean isOpen();

    /** Atomically registers a local admission before it reads or writes durable turn state. */
    default boolean tryEnter() {
        return isOpen();
    }

    /** Releases a local admission registered by {@link #tryEnter()}. */
    default void leave() {
    }
}
