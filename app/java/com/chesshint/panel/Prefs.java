package com.chesshint.panel;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;

/** All user settings and the board calibration. */
public class Prefs {
    private static final String NAME = "chesshint";
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() { return sp; }

    // ------------------------------------------------------------- calibration

    public Rect boardRect() {
        int x = sp.getInt("bx", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE) return null;
        int y = sp.getInt("by", 0), s = sp.getInt("bs", 0);
        if (s < 64) return null;
        return new Rect(x, y, x + s, y + s);
    }

    public void setBoardRect(Rect r) {
        sp.edit().putInt("bx", r.left).putInt("by", r.top).putInt("bs", r.width()).apply();
    }

    public boolean hasRect() { return boardRect() != null; }

    public void clearBoardRect() { sp.edit().remove("bx").remove("by").remove("bs").apply(); }

    // ------------------------------------------------------------- game

    /** true -> the player's colour (and the "you" side) is at the bottom = white */
    public boolean whiteBottom() { return sp.getBoolean("wbottom", true); }
    public void setWhiteBottom(boolean b) { sp.edit().putBoolean("wbottom", b).apply(); }

    public boolean auto() { return sp.getBoolean("auto", false); }
    public void setAuto(boolean b) { sp.edit().putBoolean("auto", b).apply(); }

    /** -1 = maximum strength, else a target Elo (1350..2850) */
    public int elo() { return sp.getInt("elo", -1); }
    public void setElo(int e) { sp.edit().putInt("elo", e).apply(); }

    public int movetime() { return sp.getInt("mt", 1500); }
    public void setMovetime(int ms) { sp.edit().putInt("mt", ms).apply(); }

    // ------------------------------------------------------------- appearance

    /** 0 both, 1 arrow only, 2 rings only, 3 filled squares */
    public int markerStyle() { return sp.getInt("style", Markers.STYLE_BOTH); }
    public void setMarkerStyle(int s) { sp.edit().putInt("style", s).apply(); }

    public int palette() { return sp.getInt("pal", 0); }
    public void setPalette(int p) { sp.edit().putInt("pal", p).apply(); }

    /** 0 = small, 1 = normal, 2 = large */
    public int markerSize() { return sp.getInt("msize", 1); }
    public void setMarkerSize(int s) { sp.edit().putInt("msize", s).apply(); }
    public float markerScale() { return markerSize() == 0 ? 0.82f : (markerSize() == 2 ? 1.22f : 1f); }

    public boolean showLabel() { return sp.getBoolean("label", true); }
    public void setShowLabel(boolean b) { sp.edit().putBoolean("label", b).apply(); }

    public boolean showInfo() { return sp.getBoolean("info", true); }
    public void setShowInfo(boolean b) { sp.edit().putBoolean("info", b).apply(); }

    public boolean showSideLabels() { return sp.getBoolean("sides", true); }
    public void setShowSideLabels(boolean b) { sp.edit().putBoolean("sides", b).apply(); }

    public boolean showFrame() { return sp.getBoolean("frame", false); }
    public void setShowFrame(boolean b) { sp.edit().putBoolean("frame", b).apply(); }

    /** the small status chip near the bottom of the screen */
    public boolean showChip() { return sp.getBoolean("chip", true); }
    public void setShowChip(boolean b) { sp.edit().putBoolean("chip", b).apply(); }

    public boolean vibrations() { return sp.getBoolean("vib", false); }
    public void setVibrations(boolean b) { sp.edit().putBoolean("vib", b).apply(); }

    // ------------------------------------------------------------- layout

    public int bubbleX() { return sp.getInt("bubx", -1); }
    public int bubbleY() { return sp.getInt("buby", -1); }
    public void setBubble(int x, int y) { sp.edit().putInt("bubx", x).putInt("buby", y).apply(); }

    public int bubbleSize() { return sp.getInt("bsize", 1); }
    public void setBubbleSize(int s) { sp.edit().putInt("bsize", s).apply(); }
    public float bubbleScale() { return bubbleSize() == 0 ? 0.85f : (bubbleSize() == 2 ? 1.2f : 1f); }
}
