package com.example.MobileGPT;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory list of the AI's replies (newest first) so the UI can show them
 * with a "read aloud" button. The accessibility service and MainActivity run
 * in the same process, so a static store is enough.
 */
public class ReplyStore {
    public interface Listener {
        void onChanged();
    }

    private static final int MAX_REPLIES = 50;
    private static final List<String> replies = new ArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Listener listener;

    public static synchronized void add(String text) {
        if (text == null || text.trim().isEmpty()) return;
        replies.add(0, text);
        if (replies.size() > MAX_REPLIES) {
            replies.remove(replies.size() - 1);
        }
        final Listener l = listener;
        if (l != null) {
            mainHandler.post(l::onChanged);
        }
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(replies);
    }

    public static synchronized void setListener(Listener l) {
        listener = l;
    }
}
