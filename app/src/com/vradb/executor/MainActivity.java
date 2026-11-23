package com.vradb.executor;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = 0xFF101418;
    private static final int PANEL = 0xFF1B222A;
    private static final int ACCENT = 0xFF2BD4C4;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int MUTED = 0xFF8B98A5;
    private static final int MAX_OUTPUT = 200000;

    private static final String[][] QUICK = {
            {"Model", "getprop ro.product.model"},
            {"Battery", "dumpsys battery"},
            {"My apps", "pm list packages -3"},
            {"IP", "ip -o -4 addr show wlan0"},
            {"Storage", "df -h /sdcard"},
            {"Top", "top -n 1 -m 15"},
            {"Uptime", "uptime"},
            {"Who am I", "id"},
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService stopper = Executors.newSingleThreadExecutor();
    private final AdbConnection adb = new AdbConnection();

    private EditText hostField, portField, cmdField;
    private TextView statusView, outputView;
    private ScrollView outputScroll;
    private Button connectBtn, runBtn, undoBtn, redoBtn;
    private SharedPreferences prefs;

    // Undo / redo history of the command box.
    private final ArrayList<String> history = new ArrayList<String>();
    private int historyIndex = -1;
    private boolean applyingHistory;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("vr_adb", Context.MODE_PRIVATE);
        setContentView(buildUi());
        pushHistory("");
        updateButtons();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        adb.close();
        worker.shutdownNow();
        stopper.shutdownNow();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("VR ADB Executor");
        title.setTextColor(ACCENT);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        // Connection row
        LinearLayout conn = row();
        hostField = field(prefs.getString("host", "127.0.0.1"), "IP address", InputType.TYPE_CLASS_TEXT);
        portField = field(String.valueOf(prefs.getInt("port", 5555)), "Port", InputType.TYPE_CLASS_NUMBER);
        conn.addView(hostField, new LinearLayout.LayoutParams(0, dp(56), 3f));
        conn.addView(portField, new LinearLayout.LayoutParams(0, dp(56), 1f));
        Button self = button("This headset", PANEL);
        self.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hostField.setText("127.0.0.1");
                portField.setText("5555");
            }
        });
        conn.addView(self, wrap());
        connectBtn = button("Connect", ACCENT);
        connectBtn.setTextColor(BG);
        connectBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (adb.isConnected()) disconnect();
                else connect();
            }
        });
        conn.addView(connectBtn, wrap());
        root.addView(conn);

        statusView = new TextView(this);
        statusView.setTextColor(MUTED);
        statusView.setTextSize(15);
        statusView.setPadding(dp(4), dp(6), dp(4), dp(6));
        statusView.setText("Not connected. On the target run 'adb tcpip 5555' once from a PC, then press Connect.");
        root.addView(statusView);

        // Command row
        LinearLayout cmdRow = row();
        cmdField = field("", "Shell command, e.g. pm list packages", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cmdField.setTypeface(Typeface.MONOSPACE);
        cmdField.setImeOptions(EditorInfo.IME_ACTION_GO);
        cmdField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                runCommand();
                return true;
            }
        });
        cmdField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                if (!applyingHistory) pushHistory(s.toString());
            }
        });
        cmdRow.addView(cmdField, new LinearLayout.LayoutParams(0, dp(56), 1f));
        runBtn = button("Execute", ACCENT);
        runBtn.setTextColor(BG);
        runBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (running) stopCommand();
                else runCommand();
            }
        });
        cmdRow.addView(runBtn, wrap());
        root.addView(cmdRow);

        // Undo / Redo / Clear row
        LinearLayout edit = row();
        undoBtn = button("Undo", PANEL);
        undoBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                undo();
            }
        });
        redoBtn = button("Redo", PANEL);
        redoBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                redo();
            }
        });
        Button clear = button("Clear", 0xFF5A2A2A);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                outputView.setText("");
            }
        });
        Button clearCmd = button("Clear command", PANEL);
        clearCmd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                cmdField.setText("");
            }
        });
        edit.addView(undoBtn, new LinearLayout.LayoutParams(0, dp(56), 1f));
        edit.addView(redoBtn, new LinearLayout.LayoutParams(0, dp(56), 1f));
        edit.addView(clearCmd, new LinearLayout.LayoutParams(0, dp(56), 1f));
        edit.addView(clear, new LinearLayout.LayoutParams(0, dp(56), 1f));
        root.addView(edit);

        // Quick commands
        HorizontalScrollView qs = new HorizontalScrollView(this);
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        for (final String[] q : QUICK) {
            Button b = button(q[0], PANEL);
            b.setTextSize(14);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    cmdField.setText(q[1]);
                    cmdField.setSelection(cmdField.length());
                }
            });
            quick.addView(b, wrap());
        }
        qs.addView(quick);
        root.addView(qs);

        // Output console
        outputScroll = new ScrollView(this);
        GradientDrawable obg = new GradientDrawable();
        obg.setColor(0xFF0A0D10);
        obg.setCornerRadius(dp(10));
        outputScroll.setBackground(obg);
        outputView = new TextView(this);
        outputView.setTextColor(0xFFB8F5C8);
        outputView.setTypeface(Typeface.MONOSPACE);
        outputView.setTextSize(14);
        outputView.setTextIsSelectable(true);
        outputView.setPadding(dp(12), dp(10), dp(12), dp(10));
        outputScroll.addView(outputView);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        olp.topMargin = dp(10);
        root.addView(outputScroll, olp);
        return root;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        return r;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(56));
    }

    private EditText field(String value, String hint, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(18);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(10));
        e.setBackground(bg);
        e.setPadding(dp(14), 0, dp(14), 0);
        return e;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(17);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(18), 0, dp(18), 0);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- Undo / redo

    private void pushHistory(String text) {
        if (historyIndex >= 0 && history.get(historyIndex).equals(text)) return;
        while (history.size() > historyIndex + 1) history.remove(history.size() - 1);
        history.add(text);
        if (history.size() > 300) history.remove(0);
        historyIndex = history.size() - 1;
        updateButtons();
    }

    private void undo() {
        if (historyIndex <= 0) return;
        historyIndex--;
        applyHistory();
    }

    private void redo() {
        if (historyIndex >= history.size() - 1) return;
        historyIndex++;
        applyHistory();
    }

    private void applyHistory() {
        applyingHistory = true;
        String t = history.get(historyIndex);
        cmdField.setText(t);
        cmdField.setSelection(t.length());
        applyingHistory = false;
        updateButtons();
    }

    private void updateButtons() {
        if (undoBtn == null) return;
        undoBtn.setEnabled(historyIndex > 0);
        undoBtn.setAlpha(historyIndex > 0 ? 1f : 0.4f);
        boolean canRedo = historyIndex < history.size() - 1;
        redoBtn.setEnabled(canRedo);
        redoBtn.setAlpha(canRedo ? 1f : 0.4f);
        connectBtn.setText(adb.isConnected() ? "Disconnect" : "Connect");
        runBtn.setText(running ? "Stop" : "Execute");
    }

    // ---------------------------------------------------------------- ADB

    private final AdbConnection.Listener listener = new AdbConnection.Listener() {
        public void onStatus(final String msg) {
            ui.post(new Runnable() {
                public void run() {
                    statusView.setText(msg);
                }
            });
        }

        public void onOutput(String text) {
            appendOutput(text);
        }
    };

    private void connect() {
        final String host = hostField.getText().toString().trim();
        int p;
        try {
            p = Integer.parseInt(portField.getText().toString().trim());
        } catch (NumberFormatException e) {
            p = 5555;
        }
        final int port = p;
        prefs.edit().putString("host", host).putInt("port", port).apply();
        connectBtn.setEnabled(false);
        worker.execute(new Runnable() {
            public void run() {
                String result;
                try {
                    AdbKey key = AdbKey.loadOrCreate(getFilesDir());
                    adb.connect(host, port, key, listener);
                    result = "Connected to " + host + ":" + port + "  " + shortBanner(adb.banner());
                } catch (Exception e) {
                    adb.close();
                    result = "Connection failed: " + describe(e);
                }
                final String r = result;
                ui.post(new Runnable() {
                    public void run() {
                        statusView.setText(r);
                        connectBtn.setEnabled(true);
                        updateButtons();
                    }
                });
            }
        });
    }

    private void disconnect() {
        adb.close();
        statusView.setText("Disconnected.");
        updateButtons();
    }

    private void runCommand() {
        final String cmd = cmdField.getText().toString().trim();
        if (cmd.isEmpty() || running) return;
        if (!adb.isConnected()) {
            statusView.setText("Connect first.");
            return;
        }
        running = true;
        updateButtons();
        appendOutput("$ " + cmd + "\n");
        worker.execute(new Runnable() {
            public void run() {
                try {
                    adb.shell(cmd, listener);
                } catch (Exception e) {
                    appendOutput("[error] " + describe(e) + "\n");
                    if (!adb.isConnected()) listener.onStatus("Disconnected: " + describe(e));
                }
                appendOutput("\n");
                ui.post(new Runnable() {
                    public void run() {
                        running = false;
                        updateButtons();
                    }
                });
            }
        });
    }

    private void stopCommand() {
        stopper.execute(new Runnable() {
            public void run() {
                adb.stopCurrent();
            }
        });
    }

    private void appendOutput(final String text) {
        ui.post(new Runnable() {
            public void run() {
                outputView.append(text);
                CharSequence cur = outputView.getText();
                if (cur.length() > MAX_OUTPUT) {
                    outputView.setText(cur.subSequence(cur.length() - MAX_OUTPUT / 2, cur.length()));
                }
                outputScroll.post(new Runnable() {
                    public void run() {
                        outputScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private static String shortBanner(String b) {
        int i = b.indexOf("ro.product.model=");
        if (i < 0) return "";
        int end = b.indexOf(';', i);
        return "(" + b.substring(i + 17, end < 0 ? b.length() : end) + ")";
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
