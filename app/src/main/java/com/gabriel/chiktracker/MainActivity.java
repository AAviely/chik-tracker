package com.gabriel.chiktracker;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Supplier;

/** A terminal-style screen: output on top, one input line at the bottom. All logic lives in Shell. */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0B1210;
    private static final int FG = 0xFF7CF29A;
    private static final int DIM = 0xFF3C8F58;

    private Shell shell;
    private TextView out;
    private ScrollView scroll;
    private EditText input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final File file = new File(getFilesDir(), "chik.txt");
        Store store = new Store(new Store.Persist() {
            @Override public String load() {
                if (!file.exists()) return null;
                try (FileInputStream in = new FileInputStream(file)) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                    return new String(buf.toByteArray(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return null;
                }
            }

            @Override public void save(String text) {
                File tmp = new File(getFilesDir(), "chik.txt.tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(text.getBytes(StandardCharsets.UTF_8));
                    o.getFD().sync();
                } catch (IOException e) {
                    return;
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
        });
        shell = new Shell(store, LocalDate::now);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        scroll = new ScrollView(this);
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(14);
        out.setTextColor(FG);
        out.setTextIsSelectable(true);
        int pad = dp(12);
        out.setPadding(pad, pad, pad, pad);
        scroll.addView(out);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, dp(4), pad, dp(8));

        TextView prompt = new TextView(this);
        prompt.setText("> ");
        prompt.setTypeface(Typeface.MONOSPACE);
        prompt.setTextSize(18);
        prompt.setTextColor(FG);
        bar.addView(prompt);

        input = new EditText(this);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(18);
        input.setTextColor(FG);
        input.setHintTextColor(DIM);
        input.setHint("number or m");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submit();
                return true;
            }
            return false;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button go = new Button(this);
        go.setText("Enter");
        go.setOnClickListener(v -> submit());
        bar.addView(go);

        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        print(shell.start());
    }

    private void submit() {
        String line = input.getText().toString();
        input.setText("");
        print("\n> " + line);
        String result;
        try {
            result = shell.handle(line);
        } catch (RuntimeException e) {
            result = "Error: " + e;
        }
        if (!result.isEmpty()) print(result);
    }

    private void print(String text) {
        out.append(text + "\n");
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

/** Holds every logged day plus settings. Saved as a small text file: price=, policy=, use=YYYY-MM-DD lines. */
final class Store {
    interface Persist {
        String load();
        void save(String text);
    }

    final TreeSet<LocalDate> uses = new TreeSet<>();
    double price = 30;
    Double policy = null;
    private final Persist persist;

    Store(Persist persist) {
        this.persist = persist;
        String text = null;
        try {
            text = persist.load();
        } catch (RuntimeException ignored) {
        }
        if (text == null) return;
        for (String line : text.split("\n")) {
            line = line.trim();
            int i = line.indexOf('=');
            if (i < 0) continue;
            String k = line.substring(0, i);
            String v = line.substring(i + 1).trim();
            try {
                switch (k) {
                    case "price": price = Double.parseDouble(v); break;
                    case "policy": policy = Double.parseDouble(v); break;
                    case "use": uses.add(LocalDate.parse(v)); break;
                    default: break;
                }
            } catch (RuntimeException ignored) {
                // skip a damaged line, keep the rest
            }
        }
    }

    void save() {
        StringBuilder sb = new StringBuilder();
        sb.append("price=").append(price).append('\n');
        if (policy != null) sb.append("policy=").append(policy).append('\n');
        for (LocalDate d : uses) sb.append("use=").append(d).append('\n');
        persist.save(sb.toString());
    }
}

/**
 * The whole app as a text terminal: handle(line) takes what the user typed and returns what to print.
 * No Android classes in here, so it runs and is tested on a plain JVM.
 */
final class Shell {
    private enum Mode { MENU, PAST_DATE, REMOVE, PRICE, POLICY }

    private final Store store;
    private final Supplier<LocalDate> clock;
    private Mode mode = Mode.MENU;
    private List<LocalDate> removeList = new ArrayList<>();

    Shell(Store store, Supplier<LocalDate> clock) {
        this.store = store;
        this.clock = clock;
    }

    String start() {
        return "Chik Tracker\n" + status() + "\n\n" + menu();
    }

    String handle(String raw) {
        String in = raw == null ? "" : raw.trim();
        if (mode != Mode.MENU) return handlePrompt(in);
        switch (in.toLowerCase(Locale.ROOT)) {
            case "1": return logToday();
            case "2":
                mode = Mode.PAST_DATE;
                return "Which day? Formats:\n  2026-10-14\n  14/10  or  14/10/2026\n  -2   (two days ago)\nBlank = cancel";
            case "3": return averages();
            case "4": return weekly();
            case "5": return monthly();
            case "6": return list();
            case "7": return startRemove();
            case "8":
                mode = Mode.PRICE;
                return "Cost per use now " + money(store.price) + ".\nNew price in shekels (blank = cancel):";
            case "9": return compare();
            case "q":
                mode = Mode.POLICY;
                return "Yearly price for permanent insurance in shekels\n('-' to clear, blank = cancel):";
            case "0": return export();
            case "m": case "menu": case "": return status() + "\n\n" + menu();
            default: return "Unknown option '" + in + "'. Type m for the menu.";
        }
    }

    // ---------- prompts ----------

    private String handlePrompt(String in) {
        Mode m = mode;
        mode = Mode.MENU;
        if (in.isEmpty() || in.equalsIgnoreCase("c")) return "Cancelled.";
        switch (m) {
            case PAST_DATE: {
                LocalDate d = parseDate(in);
                if (d == null) { mode = Mode.PAST_DATE; return "Could not read that date. Try 2026-10-14, 14/10 or -2. Blank = cancel."; }
                if (d.isAfter(clock.get())) return "That date is in the future. Nothing added.";
                return add(d);
            }
            case REMOVE: {
                int n;
                try { n = Integer.parseInt(in); } catch (NumberFormatException e) { n = -1; }
                if (n < 1 || n > removeList.size()) { mode = Mode.REMOVE; return "Type a number from 1 to " + removeList.size() + ", blank = cancel."; }
                LocalDate d = removeList.get(n - 1);
                store.uses.remove(d);
                store.save();
                return "Removed " + fmt(d) + ". Total uses: " + store.uses.size();
            }
            case PRICE: {
                Double v = parseMoney(in);
                if (v == null || v < 0) { mode = Mode.PRICE; return "Enter a number like 30. Blank = cancel."; }
                store.price = v;
                store.save();
                return "Cost per use is now " + money(v) + ".";
            }
            case POLICY: {
                if (in.equals("-")) { store.policy = null; store.save(); return "Permanent quote cleared."; }
                Double v = parseMoney(in);
                if (v == null || v <= 0) { mode = Mode.POLICY; return "Enter a yearly price like 6000, '-' to clear, blank = cancel."; }
                store.policy = v;
                store.save();
                return "Saved. " + compare();
            }
            default: return "";
        }
    }

    // ---------- actions ----------

    private String logToday() {
        LocalDate t = clock.get();
        if (store.uses.contains(t)) return "Today is already logged. (Use 7 to remove it.)";
        return add(t);
    }

    private String add(LocalDate d) {
        if (store.uses.contains(d)) return fmt(d) + " was already logged.";
        store.uses.add(d);
        store.save();
        return "Logged " + fmt(d) + ".\nTotal: " + store.uses.size() + " use" + (store.uses.size() == 1 ? "" : "s")
                + " = " + money(store.uses.size() * store.price) + "\n" + weekMonthLine();
    }

    private String weekMonthLine() {
        LocalDate t = clock.get();
        LocalDate ws = weekStart(t);
        int w = 0, m = 0;
        for (LocalDate d : store.uses) {
            if (!d.isBefore(ws) && !d.isAfter(t)) w++;
            if (YearMonth.from(d).equals(YearMonth.from(t))) m++;
        }
        return "This week: " + w + "   This month: " + m;
    }

    private String averages() {
        if (store.uses.isEmpty()) return "No uses logged yet. Type 1 to log today.";
        LocalDate t = clock.get();
        int n = store.uses.size();
        long elapsed = ChronoUnit.DAYS.between(store.uses.first(), t) + 1;
        double weeks = Math.max(elapsed, 7) / 7.0;
        double months = Math.max(elapsed, 30.44) / 30.44;
        double avgW = n / weeks, avgM = n / months;
        StringBuilder sb = new StringBuilder();
        sb.append("--- Averages ---\n");
        sb.append("Per week : ").append(one(avgW)).append(" days = ").append(money(avgW * store.price)).append('\n');
        sb.append("Per month: ").append(one(avgM)).append(" days = ").append(money(avgM * store.price)).append('\n');
        sb.append("Total    : ").append(n).append(" days = ").append(money(n * store.price)).append('\n');
        sb.append("Tracking : ").append(elapsed).append(" days since ").append(fmt(store.uses.first())).append('\n');
        sb.append(weekMonthLine());
        if (elapsed < 28) sb.append("\nEarly data: averages firm up after about a month.");
        return sb.toString();
    }

    private String weekly() {
        if (store.uses.isEmpty()) return "No uses logged yet.";
        LocalDate t = clock.get();
        List<LocalDate> keys = new ArrayList<>();
        for (LocalDate k = weekStart(store.uses.first()); !k.isAfter(weekStart(t)); k = k.plusDays(7)) keys.add(k);
        int[] counts = new int[keys.size()];
        for (LocalDate d : store.uses) counts[(int) (ChronoUnit.DAYS.between(keys.get(0), weekStart(d)) / 7)]++;
        StringBuilder sb = new StringBuilder("--- By week (Sun-Sat) ---\n");
        sb.append(rows(keys, counts, true));
        return sb.toString().trim();
    }

    private String monthly() {
        if (store.uses.isEmpty()) return "No uses logged yet.";
        YearMonth last = YearMonth.from(clock.get());
        List<LocalDate> keys = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(store.uses.first()); !ym.isAfter(last); ym = ym.plusMonths(1)) keys.add(ym.atDay(1));
        int[] counts = new int[keys.size()];
        YearMonth first = YearMonth.from(keys.get(0));
        for (LocalDate d : store.uses) counts[(int) ChronoUnit.MONTHS.between(first, YearMonth.from(d))]++;
        StringBuilder sb = new StringBuilder("--- By month ---\n");
        sb.append(rows(keys, counts, false));
        return sb.toString().trim();
    }

    /** Most recent first, at most 12 rows. '*' marks the current, unfinished period. */
    private String rows(List<LocalDate> keys, int[] counts, boolean weeks) {
        int max = 1;
        for (int c : counts) max = Math.max(max, c);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = keys.size() - 1; i >= 0 && shown < 12; i--, shown++) {
            String label = weeks ? weekLabel(keys.get(i)) : monthLabel(keys.get(i));
            int bar = (int) Math.round(counts[i] * 8.0 / max);
            StringBuilder b = new StringBuilder();
            for (int j = 0; j < 8; j++) b.append(j < bar ? '#' : '.');
            sb.append(pad(label, 13)).append(i == keys.size() - 1 ? '*' : ' ').append(b).append(' ')
              .append(counts[i]).append("  ").append(money(counts[i] * store.price)).append('\n');
        }
        if (keys.size() > 12) sb.append("(older ones not shown)\n");
        sb.append("* = still in progress");
        return sb.toString();
    }

    private String list() {
        if (store.uses.isEmpty()) return "No uses logged yet.";
        StringBuilder sb = new StringBuilder("--- All uses (" + store.uses.size() + ") ---\n");
        for (LocalDate d : store.uses.descendingSet()) sb.append(fmt(d)).append('\n');
        return sb.toString().trim();
    }

    private String startRemove() {
        if (store.uses.isEmpty()) return "Nothing to remove.";
        removeList = new ArrayList<>(store.uses.descendingSet());
        StringBuilder sb = new StringBuilder("Remove which? (newest first)\n");
        for (int i = 0; i < removeList.size() && i < 30; i++) sb.append(pad(String.valueOf(i + 1), 3)).append(fmt(removeList.get(i))).append('\n');
        if (removeList.size() > 30) sb.append("(30 newest shown; numbers beyond still work)\n");
        sb.append("Number, blank = cancel:");
        mode = Mode.REMOVE;
        return sb.toString();
    }

    private String compare() {
        if (store.policy == null) {
            mode = Mode.POLICY;
            return "Enter a yearly price for permanent insurance in shekels\n(or '-' to skip, blank = cancel):";
        }
        double pol = store.policy;
        double be = pol / store.price;
        StringBuilder sb = new StringBuilder("--- Chik vs permanent ---\n");
        sb.append("Permanent quote: ").append(money(pol)).append(" / year\n");
        sb.append("Break-even: ").append((int) Math.ceil(be)).append(" Chik days a year (").append(one(be / 12)).append(" a month)\n");
        if (store.uses.isEmpty()) {
            sb.append("Log some uses to see your pace.");
        } else {
            long elapsed = ChronoUnit.DAYS.between(store.uses.first(), clock.get()) + 1;
            double avgM = store.uses.size() / (Math.max(elapsed, 30.44) / 30.44);
            double yr = avgM * 12 * store.price;
            sb.append("Your pace: ").append(one(avgM)).append(" a month = ").append(money(yr)).append(" / year\n");
            if (yr < pol) sb.append("Chik is cheaper by ").append(money(pol - yr)).append(" a year.");
            else sb.append("Permanent would save about ").append(money(yr - pol)).append(" a year.");
        }
        sb.append("\n(q = change the quote)");
        return sb.toString();
    }

    private String export() {
        StringBuilder sb = new StringBuilder("--- Export (long-press to copy) ---\n");
        sb.append("price=").append(money(store.price)).append('\n');
        for (LocalDate d : store.uses) sb.append(d).append('\n');
        return sb.toString().trim();
    }

    // ---------- helpers ----------

    String status() {
        LocalDate t = clock.get();
        return fmt(t) + "\nToday: " + (store.uses.contains(t) ? "logged" : "not logged yet") + "   Total: " + store.uses.size();
    }

    private String menu() {
        return " 1  Log today's use\n"
             + " 2  Log a past day\n"
             + " 3  Averages and cost\n"
             + " 4  Weekly breakdown\n"
             + " 5  Monthly breakdown\n"
             + " 6  List all uses\n"
             + " 7  Remove an entry\n"
             + " 8  Set cost per use (" + money(store.price) + ")\n"
             + " 9  Permanent insurance check\n"
             + " q  Change permanent quote\n"
             + " 0  Export data\n"
             + " m  Show this menu";
    }

    static LocalDate weekStart(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() % 7); // Sunday = 0
    }

    private LocalDate parseDate(String s) {
        try {
            LocalDate t = clock.get();
            if (s.matches("-\\d{1,4}")) return t.minusDays(Integer.parseInt(s.substring(1)));
            if (s.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                String[] p = s.split("-");
                return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            }
            if (s.matches("\\d{1,2}[/.]\\d{1,2}([/.]\\d{4})?")) {
                String[] p = s.split("[/.]");
                int day = Integer.parseInt(p[0]), month = Integer.parseInt(p[1]);
                if (p.length == 3) return LocalDate.of(Integer.parseInt(p[2]), month, day);
                LocalDate d = LocalDate.of(t.getYear(), month, day);
                return d.isAfter(t) ? d.minusYears(1) : d;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static Double parseMoney(String s) {
        try { return Double.parseDouble(s.replace("₪", "").replace(",", ".").trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String fmt(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth() + " "
                + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getYear();
    }

    private static String weekLabel(LocalDate ws) {
        LocalDate we = ws.plusDays(6);
        String a = ws.getDayOfMonth() + "", b = we.getDayOfMonth() + " " + we.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        if (ws.getMonth() != we.getMonth()) a += " " + ws.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return a + "-" + b;
    }

    private static String monthLabel(LocalDate d) {
        return d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getYear();
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private static String one(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String money(double v) {
        return v == Math.rint(v) ? String.format(Locale.US, "₪%d", (long) v) : String.format(Locale.US, "₪%.1f", v);
    }
}
