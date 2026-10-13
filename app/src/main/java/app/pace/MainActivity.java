package app.pace;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;

/**
 * One screen: three buttons (meal, snack, coffee), two status cards that say when
 * the next food / coffee is possible, and today's list. All rules are documented in
 * the "How it counts" dialog (R.string.about_text) with their sources.
 */
public class MainActivity extends Activity {

    static final int MEAL = 0, SNACK = 1, COFFEE = 2;

    // Evidence-based limits; see about_text for sources.
    static final int DAILY_MG = 400;           // FDA, healthy adults
    static final int COFFEE_CUTOFF_MIN = 540;  // 9 h before bed (Gardiner 2023: >= 8.8 h)
    static final int FOOD_CUTOFF_MIN = 180;    // 3 h before bed (ACG 2022: 2-3 h)
    static final int DAY_START_HOUR = 4;       // entries after midnight still count as "today"

    static final int BG = 0xFF0E0F12, CARD = 0xFF1A1C21, CARD_HI = 0xFF23262D,
            TEXT = 0xFFF2F2F2, MUTED = 0xFF9AA0A6,
            GREEN = 0xFF7BD88F, AMBER = 0xFFFFB86C, RED = 0xFFFF6B6B;

    SharedPreferences prefs;
    final ArrayList<long[]> entries = new ArrayList<>(); // {time millis, kind}
    final ArrayList<String> undoStack = new ArrayList<>();
    final Handler handler = new Handler(Looper.getMainLooper());

    TextView foodTitle, foodSub, coffeeTitle, coffeeSub, coffeeMg, undoBtn;
    LinearLayout list;

    final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 30_000);
        }
    };

    // ---------- lifecycle ----------

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("pace", MODE_PRIVATE);
        load(prefs.getString("entries", ""));
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    // ---------- settings ----------

    int bedMin() { return prefs.getInt("bed", 22 * 60); }
    int mgPerCup() { return prefs.getInt("mg", 110); }
    int mealGapMin() { return prefs.getInt("mealGap", 180); }
    int snackGapMin() { return prefs.getInt("snackGap", 120); }

    // ---------- storage ----------

    void load(String s) {
        entries.clear();
        for (String part : s.split(";")) {
            String[] p = part.split(":");
            if (p.length != 2) continue;
            try {
                entries.add(new long[]{Long.parseLong(p[0]), Long.parseLong(p[1])});
            } catch (NumberFormatException ignored) {
            }
        }
        sort();
    }

    String serialize() {
        StringBuilder sb = new StringBuilder();
        for (long[] e : entries) sb.append(e[0]).append(':').append(e[1]).append(';');
        return sb.toString();
    }

    void sort() {
        Collections.sort(entries, (a, c) -> Long.compare(a[0], c[0]));
    }

    /** Snapshot for undo, then apply a change and save. */
    void change(Runnable r) {
        undoStack.add(serialize());
        if (undoStack.size() > 30) undoStack.remove(0);
        r.run();
        sort();
        long keepFrom = dayStart() - 2L * 24 * 3600_000;
        entries.removeIf(e -> e[0] < keepFrom);
        prefs.edit().putString("entries", serialize()).apply();
        render();
    }

    void undo() {
        if (undoStack.isEmpty()) return;
        load(undoStack.remove(undoStack.size() - 1));
        prefs.edit().putString("entries", serialize()).apply();
        render();
    }

    // ---------- time helpers ----------

    long now() { return System.currentTimeMillis(); }

    /** Start of the current "day" (04:00), so a late night entry still belongs to today. */
    long dayStart() {
        Calendar c = Calendar.getInstance();
        if (c.get(Calendar.HOUR_OF_DAY) < DAY_START_HOUR) c.add(Calendar.DAY_OF_MONTH, -1);
        c.set(Calendar.HOUR_OF_DAY, DAY_START_HOUR);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Clock time h:m placed inside the current day (04:00 to 04:00). */
    long atToday(int h, int m) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStart());
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        long t = c.getTimeInMillis();
        if (t < dayStart()) t += 24L * 3600_000;
        return t;
    }

    long bedtime() { return atToday(bedMin() / 60, bedMin() % 60); }

    String fmt(long t) { return DateFormat.getTimeFormat(this).format(t); }

    String fmtMin(int min) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, min / 60);
        c.set(Calendar.MINUTE, min % 60);
        return fmt(c.getTimeInMillis());
    }

    // ---------- rules ----------

    void render() {
        long now = now(), start = dayStart(), bed = bedtime();

        // Food: the latest "busy until" among today's meals and snacks.
        long foodCutoff = bed - FOOD_CUTOFF_MIN * 60_000L;
        long nextFood = 0;
        int cups = 0;
        long lastCoffee = 0;
        for (long[] e : entries) {
            if (e[0] < start) continue;
            if (e[1] == MEAL) nextFood = Math.max(nextFood, e[0] + mealGapMin() * 60_000L);
            else if (e[1] == SNACK) nextFood = Math.max(nextFood, e[0] + snackGapMin() * 60_000L);
            else if (e[1] == COFFEE) { cups++; lastCoffee = Math.max(lastCoffee, e[0]); }
        }

        if (now >= foodCutoff || nextFood >= foodCutoff) {
            foodTitle.setText(R.string.eat_closed);
            foodTitle.setTextColor(RED);
            foodSub.setText(getString(R.string.eat_closed_sub, fmt(bed)));
        } else if (nextFood > now) {
            foodTitle.setText(getString(R.string.eat_from, fmt(nextFood)));
            foodTitle.setTextColor(AMBER);
            foodSub.setText(getString(R.string.eat_last, fmt(foodCutoff)));
        } else {
            foodTitle.setText(R.string.eat_now);
            foodTitle.setTextColor(GREEN);
            foodSub.setText(getString(R.string.eat_last, fmt(foodCutoff)));
        }

        // Coffee: daily mg budget, evening cutoff, remaining cups spread evenly until the cutoff.
        long coffeeCutoff = bed - COFFEE_CUTOFF_MIN * 60_000L;
        int mg = cups * mgPerCup();
        int left = Math.max(0, (DAILY_MG - mg) / Math.max(1, mgPerCup()));
        coffeeMg.setText(getString(R.string.coffee_mg, mg));
        if (left == 0) {
            coffeeTitle.setText(R.string.coffee_limit);
            coffeeTitle.setTextColor(RED);
            coffeeSub.setText("");
        } else if (now >= coffeeCutoff) {
            coffeeTitle.setText(R.string.coffee_late);
            coffeeTitle.setTextColor(RED);
            coffeeSub.setText(getString(R.string.coffee_late_sub, fmt(bed)));
        } else {
            // Spread over the window ending 30 min before the cutoff, so the last cup isn't
            // scheduled exactly at the moment it becomes too late.
            long windowEnd = coffeeCutoff - 30 * 60_000L;
            long next = lastCoffee == 0 ? now : lastCoffee + (windowEnd - lastCoffee) / left;
            if (lastCoffee > now) next = Math.max(next, lastCoffee); // planned cup still ahead
            String sub = getResources().getQuantityString(R.plurals.cups_left, left, left)
                    + " · " + getString(R.string.coffee_last, fmt(coffeeCutoff));
            if (next <= now) {
                coffeeTitle.setText(R.string.coffee_now);
                coffeeTitle.setTextColor(GREEN);
            } else if (next >= coffeeCutoff) {
                coffeeTitle.setText(R.string.coffee_late);
                coffeeTitle.setTextColor(RED);
                sub = getString(R.string.coffee_late_sub, fmt(bed));
            } else {
                coffeeTitle.setText(getString(R.string.coffee_from, fmt(next)));
                coffeeTitle.setTextColor(AMBER);
            }
            coffeeSub.setText(sub);
        }

        undoBtn.setVisibility(undoStack.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        renderList(start, now);
    }

    void renderList(long start, long now) {
        list.removeAllViews();
        boolean any = false;
        for (long[] e : entries) {
            if (e[0] < start) continue;
            any = true;
            list.addView(entryRow(e, e[0] > now));
        }
        if (!any) {
            TextView t = text(getString(R.string.nothing), 15, MUTED);
            t.setPadding(0, dp(8), 0, dp(8));
            list.addView(t);
        }
    }

    // ---------- actions ----------

    void add(int kind, long t) {
        change(() -> entries.add(new long[]{t, kind}));
        Toast.makeText(this, getString(R.string.logged, kindName(kind), fmt(t)), Toast.LENGTH_SHORT).show();
    }

    void pickTime(long initial, TimeCallback cb) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(initial);
        new TimePickerDialog(this, R.style.Dialog, (v, h, m) -> cb.on(atToday(h, m)),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(this)).show();
    }

    interface TimeCallback { void on(long t); }

    String kindName(long kind) {
        return getString(kind == MEAL ? R.string.meal : kind == SNACK ? R.string.snack : R.string.cup);
    }

    String kindIcon(long kind) {
        return kind == MEAL ? "🍽" : kind == SNACK ? "🍎" : "☕";
    }

    void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        final int[] bed = {bedMin()};
        Button bedBtn = new Button(this);
        bedBtn.setText(getString(R.string.bedtime) + ": " + fmtMin(bed[0]));
        bedBtn.setOnClickListener(v -> new TimePickerDialog(this, R.style.Dialog, (tp, h, m) -> {
            bed[0] = h * 60 + m;
            bedBtn.setText(getString(R.string.bedtime) + ": " + fmtMin(bed[0]));
        }, bed[0] / 60, bed[0] % 60, DateFormat.is24HourFormat(this)).show());
        box.addView(bedBtn);

        EditText mg = field(box, R.string.mg_per_cup, String.valueOf(mgPerCup()), false);
        EditText meal = field(box, R.string.meal_gap, hours(mealGapMin()), true);
        EditText snack = field(box, R.string.snack_gap, hours(snackGapMin()), true);

        new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle(R.string.settings)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    SharedPreferences.Editor ed = prefs.edit().putInt("bed", bed[0]);
                    int v = parseInt(mg.getText().toString());
                    if (v > 0 && v <= 400) ed.putInt("mg", v);
                    int mm = parseHours(meal.getText().toString());
                    if (mm > 0) ed.putInt("mealGap", mm);
                    int sm = parseHours(snack.getText().toString());
                    if (sm > 0) ed.putInt("snackGap", sm);
                    ed.apply();
                    render();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    EditText field(LinearLayout box, int label, String value, boolean decimal) {
        TextView l = text(getString(label), 14, MUTED);
        l.setPadding(0, dp(14), 0, 0);
        box.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setInputType(InputType.TYPE_CLASS_NUMBER
                | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        box.addView(e);
        return e;
    }

    static String hours(int min) {
        return min % 60 == 0 ? String.valueOf(min / 60) : String.valueOf(min / 60.0);
    }

    static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    static int parseHours(String s) {
        try {
            double h = Double.parseDouble(s.trim().replace(',', '.'));
            return h > 0 && h <= 12 ? (int) Math.round(h * 60) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    void showAbout() {
        TextView t = text(getString(R.string.about_text), 15, TEXT);
        t.setLineSpacing(0, 1.2f);
        t.setAutoLinkMask(Linkify.WEB_URLS);
        t.setText(getString(R.string.about_text));
        t.setLinkTextColor(GREEN);
        t.setPadding(dp(24), dp(8), dp(24), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle(R.string.about)
                .setView(sv)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    // ---------- UI ----------

    View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        scroll.addView(root);

        // Header: name, undo, info, settings.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.app_name), 26, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        undoBtn = pill("↶ " + getString(R.string.undo));
        undoBtn.setOnClickListener(v -> undo());
        head.addView(undoBtn);
        TextView info = pill("ⓘ");
        info.setOnClickListener(v -> showAbout());
        head.addView(info);
        TextView gear = pill("⚙");
        gear.setOnClickListener(v -> showSettings());
        head.addView(gear);
        root.addView(head);

        // Status cards.
        LinearLayout food = card(root, getString(R.string.food));
        foodTitle = text("", 22, TEXT);
        foodTitle.setTypeface(Typeface.DEFAULT_BOLD);
        food.addView(foodTitle);
        foodSub = text("", 15, MUTED);
        food.addView(foodSub);

        LinearLayout coffee = card(root, getString(R.string.coffee));
        coffeeTitle = text("", 22, TEXT);
        coffeeTitle.setTypeface(Typeface.DEFAULT_BOLD);
        coffee.addView(coffeeTitle);
        coffeeSub = text("", 15, MUTED);
        coffee.addView(coffeeSub);
        coffeeMg = text("", 13, MUTED);
        coffeeMg.setPadding(0, dp(6), 0, 0);
        coffee.addView(coffeeMg);

        // Big buttons. Tap = now, hold = pick a time.
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(16), 0, 0);
        row.addView(bigButton(MEAL), weight());
        row.addView(bigButton(SNACK), weight());
        row.addView(bigButton(COFFEE), weight());
        root.addView(row);

        TextView hint = text(getString(R.string.hint), 13, MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        root.addView(hint);

        TextView today = text(getString(R.string.today), 13, MUTED);
        today.setTypeface(Typeface.DEFAULT_BOLD);
        today.setPadding(dp(4), dp(24), 0, dp(6));
        root.addView(today);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        return scroll;
    }

    LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(110), 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    View bigButton(int kind) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(CARD_HI, 20));
        b.setClickable(true);
        b.setFocusable(true);
        TextView icon = text(kindIcon(kind), 34, TEXT);
        icon.setGravity(Gravity.CENTER);
        b.addView(icon);
        TextView label = text(kindName(kind), 15, TEXT);
        label.setGravity(Gravity.CENTER);
        b.addView(label);
        b.setOnClickListener(v -> add(kind, now()));
        b.setOnLongClickListener(v -> {
            pickTime(now(), t -> add(kind, t));
            return true;
        });
        return b;
    }

    View entryRow(long[] e, boolean planned) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(4), dp(4), dp(4));
        r.setBackground(round(CARD, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(6));
        r.setLayoutParams(lp);

        TextView time = text(fmt(e[0]), 17, planned ? MUTED : TEXT);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setMinWidth(dp(72));
        r.addView(time);
        String label = kindIcon(e[1]) + "  " + kindName(e[1])
                + (planned ? "  · " + getString(R.string.planned) : "");
        r.addView(text(label, 16, planned ? MUTED : TEXT), new LinearLayout.LayoutParams(0, -2, 1));

        TextView del = text("✕", 18, MUTED);
        del.setPadding(dp(16), dp(10), dp(16), dp(10));
        del.setOnClickListener(v -> {
            change(() -> entries.remove(e));
            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show();
        });
        r.addView(del);

        r.setOnClickListener(v -> pickTime(e[0], t -> change(() -> e[0] = t)));
        return r;
    }

    LinearLayout card(LinearLayout root, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(14), dp(18), dp(16));
        c.setBackground(round(CARD, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(16), 0, 0);
        root.addView(c, lp);
        TextView l = text(label, 12, MUTED);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setLetterSpacing(0.12f);
        l.setPadding(0, 0, 0, dp(6));
        c.addView(l);
        return c;
    }

    TextView pill(String s) {
        TextView t = text(s, 16, TEXT);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(round(CARD, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(6), 0, 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
