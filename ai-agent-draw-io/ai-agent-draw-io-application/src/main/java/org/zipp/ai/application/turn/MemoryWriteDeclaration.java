package org.zipp.ai.application.turn;

public sealed interface MemoryWriteDeclaration
        permits NoMemoryWrite, RememberDecisionDeclaration {
}
