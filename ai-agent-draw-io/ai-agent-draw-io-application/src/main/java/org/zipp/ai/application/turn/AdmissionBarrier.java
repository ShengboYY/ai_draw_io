package org.zipp.ai.application.turn;

/** Local pause/drain barrier used around migration generation changes. */
public interface AdmissionBarrier {

    void pauseAndDrain();

    void resume();

    boolean isOpen();
}
