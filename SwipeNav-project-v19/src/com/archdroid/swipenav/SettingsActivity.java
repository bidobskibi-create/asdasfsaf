package com.archdroid.swipenav;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private interface BoolSet { void set(boolean on); }

    private Toggle.Listener bool(final SharedPreferences sp, final String key) {
        return new Toggle.Listener() {
            public void on(boolean v) { sp.edit().putBoolean(key, v).commit(); }
        };
    }

    private Slider.Listener num(final SharedPreferences sp, final String key) {
        return new Slider.Listener() {
            public void on(int v, boolean user) { sp.edit().putInt(key, v).commit(); }
        };
    }

    /** One editable gesture: tap it, pick an action from a list. */
    private void gestureRow(LinearLayout card, final String title, final String key, final int def,
                            final SharedPreferences sp) {
        final TextView[] val = new TextView[1];
        val[0] = Ui.valueRow(card, title, null, Actions.LABELS[Actions.sanitize(sp.getInt(key, def))],
                new View.OnClickListener() {
                    public void onClick(View v) {
                        int cur = Actions.sanitize(sp.getInt(key, def));
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle(title)
                                .setSingleChoiceItems(Actions.LABELS, cur, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dlg, int which) {
                                        sp.edit().putInt(key, which).commit();
                                        val[0].setText(Actions.LABELS[which] + "  \u203A");
                                        dlg.dismiss();
                                    }
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                });
    }

    /** A row that opens a pick-one list and stores the chosen index. */
    private void choiceRow(LinearLayout card, final String title, String sub, final String key, final int def,
                           final String[] names, final SharedPreferences sp) {
        final TextView[] val = new TextView[1];
        val[0] = Ui.valueRow(card, title, sub, names[Math.max(0, Math.min(names.length - 1, sp.getInt(key, def)))],
                new View.OnClickListener() {
                    public void onClick(View v) {
                        new AlertDialog.Builder(SettingsActivity.this).setTitle(title)
                                .setSingleChoiceItems(names, Math.max(0, Math.min(names.length - 1, sp.getInt(key, def))),
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface dlg, int w) {
                                                sp.edit().putInt(key, w).commit();
                                                val[0].setText(names[w] + "  \u203A");
                                                dlg.dismiss();
                                            }
                                        }).setNegativeButton("Cancel", null).show();
                    }
                });
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startService(new Intent(this, SwipeNavService.class));
        final SharedPreferences sp = getSharedPreferences(SwipeNavService.PREFS, Context.MODE_PRIVATE);
        LinearLayout root = Ui.page(this, "Swipe Navigation", "Gestures, control panel and lock");

        LinearLayout c1 = Ui.card(root, "Navigation");
        Ui.toggleRow(c1, "Swipe gestures", "Hides the on-screen buttons. Turn off to bring them back.",
                Settings.System.getInt(getContentResolver(), "dev_force_show_navbar", 1) == 0,
                new Toggle.Listener() {
                    public void on(boolean v) {
                        try { Settings.System.putInt(getContentResolver(), "dev_force_show_navbar", v ? 0 : 1); }
                        catch (Throwable t) {
                            Toast.makeText(SettingsActivity.this, "Could not change setting", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        Ui.toggleRow(c1, "Show swipe bar", "The thin pill at the bottom edge. Gestures still work when hidden.",
                sp.getBoolean(SwipeNavService.KEY_SHOW_BAR, true), bool(sp, SwipeNavService.KEY_SHOW_BAR));

        LinearLayout cg = Ui.card(root, "Gestures");
        Ui.note(cg, "Tap a gesture to choose what it does. \"Force close app\" stops the app you are in.");
        gestureRow(cg, "\u2190   Swipe left", SwipeNavService.KEY_ACT_LEFT, Actions.BACK, sp);
        gestureRow(cg, "\u2192   Swipe right", SwipeNavService.KEY_ACT_RIGHT, Actions.BACK, sp);
        gestureRow(cg, "\u2191   Swipe up", SwipeNavService.KEY_ACT_UP, Actions.HOME, sp);
        gestureRow(cg, "\u2191\u2022  Up and hold, or far", SwipeNavService.KEY_ACT_HOLD, Actions.RECENTS, sp);
        Ui.toggleRow(cg, "SwipeNav recents screen", "Bouncy recents with a close button on every app. Off uses Android's own recents.",
                sp.getBoolean("own_recents", true), bool(sp, "own_recents"));
        Ui.toggleRow(cg, "Show free RAM in recents", "Live free memory with a bar; it updates after Clear all.",
                sp.getBoolean("recents_ram", true), bool(sp, "recents_ram"));
        Ui.sliderRow(cg, "Apps shown in recents", " apps", 4, 12, sp.getInt("recents_max", 8), num(sp, "recents_max"));
        Ui.link(cg, "Open recents now", new View.OnClickListener() {
            public void onClick(View v) { sendBroadcast(new Intent(SwipeNavService.ACTION_RECENTS)); }
        });
        Ui.link(cg, "Reset gestures to default", new View.OnClickListener() {
            public void onClick(View v) {
                sp.edit().remove(SwipeNavService.KEY_ACT_LEFT).remove(SwipeNavService.KEY_ACT_RIGHT)
                        .remove(SwipeNavService.KEY_ACT_UP).remove(SwipeNavService.KEY_ACT_HOLD).commit();
                recreate();
            }
        });

        LinearLayout cp = Ui.card(root, "Position and size");
        Ui.sliderRow(cp, "Position (0 left, 100 right)", "%", 0, 100, sp.getInt(SwipeNavService.KEY_POS, 50), num(sp, SwipeNavService.KEY_POS));
        Ui.sliderRow(cp, "Hitbox width", "%", 20, 100, sp.getInt(SwipeNavService.KEY_WIDTH, 100), num(sp, SwipeNavService.KEY_WIDTH));
        Ui.sliderRow(cp, "Hitbox height", " dp", 12, 80, sp.getInt(SwipeNavService.KEY_HEIGHT, 24), num(sp, SwipeNavService.KEY_HEIGHT));
        Ui.toggleRow(cp, "Show hitbox preview", "Tints the touch area so you can see it while adjusting.",
                sp.getBoolean(SwipeNavService.KEY_HITBOX, false), bool(sp, SwipeNavService.KEY_HITBOX));

        LinearLayout cf = Ui.card(root, "Feel");
        Ui.sliderRow(cf, "Swipe sensitivity", "%", 50, 150, sp.getInt(SwipeNavService.KEY_SENS, 100), num(sp, SwipeNavService.KEY_SENS));
        Ui.sliderRow(cf, "Bar opacity", "%", 20, 100, sp.getInt(SwipeNavService.KEY_OPACITY, 100), num(sp, SwipeNavService.KEY_OPACITY));
        Ui.toggleRow(cf, "Haptic feedback", "Short vibration when a gesture triggers.",
                sp.getBoolean(SwipeNavService.KEY_HAPTIC, true), bool(sp, SwipeNavService.KEY_HAPTIC));
        Ui.link(cf, "Reset position and feel to defaults", new View.OnClickListener() {
            public void onClick(View v) {
                sp.edit().remove(SwipeNavService.KEY_POS).remove(SwipeNavService.KEY_WIDTH)
                        .remove(SwipeNavService.KEY_HEIGHT).remove(SwipeNavService.KEY_SENS)
                        .remove(SwipeNavService.KEY_OPACITY).remove(SwipeNavService.KEY_HAPTIC)
                        .remove(SwipeNavService.KEY_HITBOX).remove(SwipeNavService.KEY_PANEL_W)
                        .remove(SwipeNavService.KEY_PANEL_RIGHT).commit();
                recreate();
            }
        });

        LinearLayout cc = Ui.card(root, "Control panel");
        Ui.toggleRow(cc, "Control panel", "Swipe down from the top edge. While on, it replaces Android's default quick settings.",
                sp.getBoolean(SwipeNavService.KEY_PANEL, true), bool(sp, SwipeNavService.KEY_PANEL));
        Ui.sliderRow(cc, "Trigger width", "%", 20, 100, sp.getInt(SwipeNavService.KEY_PANEL_W, 50), num(sp, SwipeNavService.KEY_PANEL_W));
        Ui.toggleRow(cc, "Trigger on the right side", "Off = left side. Pulling down on the other side still opens notifications.",
                sp.getBoolean(SwipeNavService.KEY_PANEL_RIGHT, true), bool(sp, SwipeNavService.KEY_PANEL_RIGHT));
        Ui.toggleRow(cc, "iOS-style volume HUD", "Centered rounded volume indicator with a dot meter.",
                sp.getBoolean(SwipeNavService.KEY_VOL_HUD, true), bool(sp, SwipeNavService.KEY_VOL_HUD));
        Ui.link(cc, "Open control panel now", new View.OnClickListener() {
            public void onClick(View v) { sendBroadcast(new Intent(SwipeNavService.ACTION_TOGGLE)); }
        });

        LinearLayout cs = Ui.card(root, "Control panel look");
        final String[] styles = {"iOS style", "MIUI style"};
        final TextView[] sv = new TextView[1];
        sv[0] = Ui.valueRow(cs, "Panel style", "iOS: rounded tiles and sliders. MIUI: toggle grid with labels.",
                styles[sp.getInt("panel_style", 0)], new View.OnClickListener() {
                    public void onClick(View v) {
                        new AlertDialog.Builder(SettingsActivity.this).setTitle("Panel style")
                                .setSingleChoiceItems(styles, sp.getInt("panel_style", 0), new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dlg, int w) {
                                        sp.edit().putInt("panel_style", w).commit();
                                        sv[0].setText(styles[w] + "  \u203A");
                                        dlg.dismiss();
                                    }
                                }).setNegativeButton("Cancel", null).show();
                    }
                });
        Ui.sliderRow(cs, "Corner roundness", "%", 0, 150, sp.getInt("panel_round", 100), num(sp, "panel_round"));
        Ui.sliderRow(cs, "Panel opacity", "%", 30, 100, sp.getInt("panel_opacity", 90), num(sp, "panel_opacity"));
        Ui.sliderRow(cs, "Icon size", "%", 70, 130, sp.getInt("panel_icon", 100), num(sp, "panel_icon"));
        Ui.toggleRow(cs, "Show labels", "Names under the toggles (MIUI style)", sp.getBoolean("panel_labels", true), bool(sp, "panel_labels"));
        Ui.valueRow(cs, "Accent colour", "Colour of switched-on toggles. MIUI default is orange.", null, new View.OnClickListener() {
            public void onClick(View v) { accentDialog(sp); }
        });
        Ui.toggleRow(cs, "One accent colour in iOS style", "Off keeps each iOS tile its own colour", sp.getBoolean("panel_one_accent", false), bool(sp, "panel_one_accent"));

        LinearLayout cr = Ui.card(root, "Recents look");
        final int st = Math.max(0, Math.min(3, sp.getInt("rc_style", 0)));
        // per-style defaults, so the controls below show what you actually get
        final int[] defAnim = {1, 1, 0, 0}, defCard = {0, 5, 3, 4}, defDim = {70, 80, 70, 100};
        final boolean[] defClose = {true, true, false, true}, defPill = {true, false, false, false},
                defDepth = {true, false, true, false};
        final String[] styleNames = {"Classic", "MIUI", "iOS", "Windows Phone"};
        final TextView[] stv = new TextView[1];
        stv[0] = Ui.valueRow(cr, "Recents style", "Classic: bouncy strip. MIUI: orange grid. iOS: big cards. Windows Phone: flat flip tiles.",
                styleNames[st], new View.OnClickListener() {
                    public void onClick(View v) {
                        new AlertDialog.Builder(SettingsActivity.this).setTitle("Recents style")
                                .setSingleChoiceItems(styleNames, st, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dlg, int w) {
                                        sp.edit().putInt("rc_style", w).commit();
                                        dlg.dismiss();
                                        recreate();      // refresh the controls below with that style's defaults
                                    }
                                }).setNegativeButton("Cancel", null).show();
                    }
                });
        Ui.link(cr, "Preview recents now", new View.OnClickListener() {
            public void onClick(View v) { sendBroadcast(new Intent(SwipeNavService.ACTION_RECENTS)); }
        });
        choiceRow(cr, "Animation", "Smooth settles softly, Extra bouncy overshoots more.", "rc_bounce", defAnim[st],
                new String[]{"Smooth", "Bouncy", "Extra bouncy"}, sp);
        choiceRow(cr, "Card colour", "Dark, Black, Light, Glass (see-through), Accent or Gray.", "rc_card", defCard[st],
                new String[]{"Dark", "Black", "Light", "Glass", "Accent", "Gray"}, sp);
        Ui.sliderRow(cr, "Card size", "%", 70, 130, sp.getInt("rc_size", 100), num(sp, "rc_size"));
        Ui.sliderRow(cr, "Corner roundness", "%", 0, 150, sp.getInt("rc_round", 100), num(sp, "rc_round"));
        Ui.sliderRow(cr, "Icon size", "%", 60, 140, sp.getInt("rc_icon", 100), num(sp, "rc_icon"));
        Ui.sliderRow(cr, "Position from top (Classic and iOS)", "%", 0, 100, sp.getInt("rc_pos", 30), num(sp, "rc_pos"));
        Ui.sliderRow(cr, "Background dim", "%", 0, 100, sp.getInt("rc_dim", defDim[st]), num(sp, "rc_dim"));
        Ui.toggleRow(cr, "Show app names", "Name on each card.", sp.getBoolean("rc_labels", true), bool(sp, "rc_labels"));
        Ui.toggleRow(cr, "Show close button", "The x on each card. Swiping a card away still closes it.", sp.getBoolean("rc_close", defClose[st]), bool(sp, "rc_close"));
        Ui.toggleRow(cr, "Show accent bar", "Small coloured bar at the bottom of each card (Classic).", sp.getBoolean("rc_pill", defPill[st]), bool(sp, "rc_pill"));
        Ui.toggleRow(cr, "Depth effect", "Cards near the screen edges are smaller and dimmer (strip styles).", sp.getBoolean("rc_depth", defDepth[st]), bool(sp, "rc_depth"));
        Ui.toggleRow(cr, "Snap to cards", "Scrolling glides to the nearest card (strip styles).", sp.getBoolean("rc_snap", true), bool(sp, "rc_snap"));
        Ui.valueRow(cr, "Accent colour", "Shared with the control panel. Default depends on the style.", null, new View.OnClickListener() {
            public void onClick(View v) { accentDialog(sp); }
        });
        Ui.link(cr, "Reset this style's look", new View.OnClickListener() {
            public void onClick(View v) {
                sp.edit().remove("rc_bounce").remove("rc_card").remove("rc_size").remove("rc_round").remove("rc_icon")
                        .remove("rc_pos").remove("rc_dim").remove("rc_labels").remove("rc_close").remove("rc_pill")
                        .remove("rc_depth").remove("rc_snap").remove("recents_ram").remove("recents_max").commit();
                recreate();
            }
        });

        LinearLayout cl = Ui.card(root, "Lock screen");
        LockRows.add(this, cl);
    }

    private static final int[] SWATCH = {0xFFFF6A00, 0xFFE51400, 0xFFD80073, 0xFFAA00FF, 0xFF6A00FF,
            0xFF0050EF, 0xFF1BA1E2, 0xFF00ABA9, 0xFF008A00, 0xFF60A917,
            0xFFA4C400, 0xFFE3C800, 0xFFF0A30A, 0xFFFA6800, 0xFF825A2C,
            0xFF647687, 0xFF76608A, 0xFFF472D0, 0xFF30D158, 0xFF5E5CE6};

    private void accentDialog(final SharedPreferences sp) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        final AlertDialog[] dlg = new AlertDialog[1];
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            for (int c = 0; c < 5; c++) {
                final int color = SWATCH[r * 5 + c];
                View sw = new View(this);
                sw.setBackgroundColor(color);
                sw.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        sp.edit().putInt("panel_accent", color).commit();
                        dlg[0].dismiss();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (46 * d), (int) (46 * d));
                lp.setMargins((int) (3 * d), (int) (3 * d), (int) (3 * d), (int) (3 * d));
                row.addView(sw, lp);
            }
            col.addView(row);
        }
        TextView def = new TextView(this);
        def.setText("Use the style's default colours");
        def.setTextSize(16);
        def.setPadding((int) (6 * d), (int) (14 * d), 0, (int) (6 * d));
        def.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sp.edit().putInt("panel_accent", 0).commit();
                dlg[0].dismiss();
            }
        });
        col.addView(def);
        dlg[0] = new AlertDialog.Builder(this).setTitle("Accent colour").setView(col).create();
        dlg[0].show();
    }
}
