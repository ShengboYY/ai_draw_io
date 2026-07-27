package org.zipp.ai.trigger.http.turn;

/** Writer boundary used by the NDJSON adapter; delivery errors stay outside business execution. */
@FunctionalInterface
public interface NdjsonLineWriter {

    void write(String line) throws Exception;
}
