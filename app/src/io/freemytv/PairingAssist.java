package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pairs the app with "Wireless debugging" by itself. The TV's pairing dialog closes as soon as another screen comes on
 * top, so nobody can type its code into the app: this service reads the 6 digits and the port while the dialog is
 * open, pairs in the background, and turns itself off. It only listens to the TV's Settings app (see
 * res/xml/pairing_assist.xml) and only looks for that code and port.
 */
public class PairingAssist extends AccessibilityService {
    private static final Pattern CODE = Pattern.compile("^\\s*(\\d{3})\\s?(\\d{3})\\s*$");
    private static final Pattern ADDRESS = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}|\\[[0-9a-fA-F:]+\\]):(\\d{2,5})");

    private final Handler main = new Handler(Looper.getMainLooper());
    /** The code being tried or last tried: a dialog keeps its code, a new dialog brings a new one. */
    private volatile String tried;
    private volatile boolean pairing;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (pairing) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String[] found = new String[2];   // the code, the port
        try {
            scan(root, found, 0);
        } finally {
            root.recycle();
        }
        // Both on the same screen, as only the pairing dialog shows them: a lone 6-digit text elsewhere isn't a code.
        if (found[0] == null || found[1] == null || found[0].equals(tried)) return;
        pair(found[0], Integer.parseInt(found[1]));
    }

    /** Looks through the dialog's texts for the code and the "address:port" line. */
    private static void scan(AccessibilityNodeInfo node, String[] found, int depth) {
        if (depth > 30) return;
        CharSequence text = node.getText();
        if (text != null) {
            Matcher code = CODE.matcher(text);
            if (code.matches()) found[0] = code.group(1) + code.group(2);
            Matcher address = ADDRESS.matcher(text);
            if (address.find()) found[1] = address.group(2);
        }
        for (int i = 0; i < node.getChildCount() && (found[0] == null || found[1] == null); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                scan(child, found, depth + 1);
            } finally {
                child.recycle();
            }
        }
    }

    private void pair(String code, int shownPort) {
        tried = code;
        pairing = true;
        new Thread(() -> {
            try {
                AdbTls.pair(this, shownPort, code);
                main.post(this::paired);
            } catch (IOException | RuntimeException e) {
                AppLog.w("pairing assistant: " + e.getMessage());
                String why = e.getMessage();
                main.post(() -> toast(Ui.tr("Free My TV: não deu para parear (", "Free My TV: couldn't pair (",
                        "Free My TV: no se pudo emparejar (") + why + ")"));
            } finally {
                pairing = false;
            }
        }, "pairing").start();
    }

    /** Done: the dialog closes by itself; back to Prepare the TV, and this service is no longer needed. */
    private void paired() {
        toast(Ui.tr("Free My TV pareado com a TV", "Free My TV paired with the TV", "Free My TV emparejado con la TV"));
        try {
            startActivity(new Intent(this, SettingsActivity.class).putExtra("prepare", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (RuntimeException e) {
            AppLog.w("pairing assistant: " + e.getMessage());
        }
        disableSelf();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    @Override public void onInterrupt() {}
}
