package com.intellij.ml.llm.chat.notifications;

import com.intellij.ml.llm.chat.shared.ChatSessionNotificationType;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;

public final class AgentCompletionSoundPatchSupport {
    private static final long MIN_INTERVAL_MILLIS = 1_500L;
    private static volatile long lastBeepMillis;

    private AgentCompletionSoundPatchSupport() {}

    public static void playIfFocused(boolean toolWindowFocused, ChatSessionNotificationType type) {
        if (!toolWindowFocused || type != ChatSessionNotificationType.TaskFinished) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBeepMillis < MIN_INTERVAL_MILLIS) {
            return;
        }
        lastBeepMillis = now;
        Runnable beep = () -> {
            try {
                if (!GraphicsEnvironment.isHeadless()) {
                    Toolkit.getDefaultToolkit().beep();
                }
            } catch (Throwable ignored) {
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            beep.run();
        } else {
            SwingUtilities.invokeLater(beep);
        }
    }
}
