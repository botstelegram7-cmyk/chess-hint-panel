package com.chesshint.panel;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings. Everything the panel draws or does can be changed here and every change is
 * applied to the running panel immediately (the live preview at the top shows exactly
 * what will appear on the board).
 */
public class SettingsActivity extends Activity {

    private Prefs prefs;
    private MarkerPreviewView preview;
    private LinearLayout styleRow, sizeRow, paletteRow, strengthRow, timeRow, sideRow;
    private TextView bubbleRowLabel, calibrationLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 14);
        root.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 26));
        sv.addView(root);

        // -------------------------------------------------- slim top bar
        LinearLayout bar = Ui.row(this);
        TextView back = Ui.text(this, "‹", 26f, Ui.TEXT, true);
        back.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 14), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        bar.addView(Ui.text(this, "Settings", 17f, Ui.TEXT, true));
        root.addView(bar, Ui.lpTop(this, 0, 6));

        // -------------------------------------------------- MARKER section
        LinearLayout marker = Ui.card(this);
        marker.addView(Ui.sectionTitle(this, "HOW THE MOVE IS SHOWN"));

        preview = new MarkerPreviewView(this);
        marker.addView(preview);

        TextView live = Ui.text(this, "Live preview of your board", 11f, Ui.TEXT_DIM, false);
        live.setGravity(Gravity.CENTER);
        marker.addView(live, Ui.lpTop(this, 0, 2));

        marker.addView(Ui.text(this, "Style", 12.5f, Ui.TEXT_DIM, false), Ui.lpTop(this, 0, 14));
        styleRow = Ui.chipRow(this);
        marker.addView(styleRow, Ui.lpTop(this, 0, 6));

        marker.addView(Ui.text(this, "Colours", 12.5f, Ui.TEXT_DIM, false), Ui.lpTop(this, 0, 12));
        paletteRow = Ui.chipRow(this);
        marker.addView(paletteRow, Ui.lpTop(this, 0, 6));

        marker.addView(Ui.text(this, "Size", 12.5f, Ui.TEXT_DIM, false), Ui.lpTop(this, 0, 12));
        sizeRow = Ui.chipRow(this);
        marker.addView(sizeRow, Ui.lpTop(this, 0, 6));

        marker.addView(Ui.divider(this));
        marker.addView(Ui.switchRow(this, "Move text on the board", "shows the piece and both squares, e.g. ♘ g1 → f3",
                prefs.showLabel(), (v, checked) -> {
                    prefs.setShowLabel(checked);
                    push();
                }));
        marker.addView(Ui.switchRow(this, "Engine line", "depth and score printed under the board",
                prefs.showInfo(), (v, checked) -> {
                    prefs.setShowInfo(checked);
                    push();
                }));
        marker.addView(Ui.switchRow(this, "Enemy / You labels", "thin labels above and below the board",
                prefs.showSideLabels(), (v, checked) -> {
                    prefs.setShowSideLabels(checked);
                    push();
                }));
        marker.addView(Ui.switchRow(this, "Board frame + grid", "outline of the detected board",
                prefs.showFrame(), (v, checked) -> {
                    prefs.setShowFrame(checked);
                    push();
                }));
        marker.addView(Ui.switchRow(this, "Status messages", "small pill near the bottom of the screen",
                prefs.showChip(), (v, checked) -> {
                    prefs.setShowChip(checked);
                    push();
                }));
        root.addView(marker, Ui.lpTop(this, 0, 14));

        // -------------------------------------------------- PLAY section
        LinearLayout play = Ui.card(this);
        play.addView(Ui.sectionTitle(this, "PLAY"));
        play.addView(Ui.text(this, "My side (bottom of the board)", 12.5f, Ui.TEXT_DIM, false));
        sideRow = Ui.chipRow(this);
        play.addView(sideRow, Ui.lpTop(this, 0, 6));

        play.addView(Ui.text(this, "Engine strength", 12.5f, Ui.TEXT_DIM, false), Ui.lpTop(this, 0, 12));
        strengthRow = Ui.chipRow(this);
        play.addView(strengthRow, Ui.lpTop(this, 0, 6));

        play.addView(Ui.text(this, "Thinking time per hint", 12.5f, Ui.TEXT_DIM, false), Ui.lpTop(this, 0, 12));
        timeRow = Ui.chipRow(this);
        play.addView(timeRow, Ui.lpTop(this, 0, 6));

        play.addView(Ui.divider(this));
        play.addView(Ui.switchRow(this, "Auto hints", "reads the screen and shows your move automatically on your turn",
                prefs.auto(), (v, checked) -> {
                    prefs.setAuto(checked);
                    push();
                }));
        play.addView(Ui.switchRow(this, "Vibrate on new hint", null, prefs.vibrations(), (v, checked) -> {
            prefs.setVibrations(checked);
            push();
        }));
        root.addView(play, Ui.lpTop(this, 0, 14));

        // -------------------------------------------------- BOARD section
        LinearLayout boardCard = Ui.card(this);
        boardCard.addView(Ui.sectionTitle(this, "BOARD"));
        calibrationLabel = Ui.text(this, "", 12.5f, Ui.TEXT_DIM, false);
        boardCard.addView(calibrationLabel);
        LinearLayout br = Ui.row(this);
        br.addView(Ui.ghostButton(this, "Fit frame", v -> call(0)), Ui.lpTop(this, 0, 10));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(this, 8), 1);
        br.addView(new View(this), sp);
        br.addView(Ui.ghostButton(this, "Auto detect", v -> call(1)), Ui.lpTop(this, 0, 10));
        boardCard.addView(br, Ui.lpTop(this, 0, 8));
        LinearLayout br2 = Ui.row(this);
        br2.addView(Ui.ghostButton(this, "Fix pieces", v -> call(2)), Ui.lpTop(this, 0, 8));
        br2.addView(new View(this), new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        br2.addView(Ui.ghostButton(this, "New game", v -> call(3)), Ui.lpTop(this, 0, 8));
        boardCard.addView(br2);

        boardCard.addView(Ui.text(this, "Bubble size", 12.5f, Ui.TEXT_DIM, false), Ui.lpTop(this, 0, 14));
        bubbleRowLabel = Ui.text(this, "", 12.5f, Ui.TEXT, false);
        LinearLayout bubbleRow = Ui.chipRow(this);
        for (int i = 0; i < 3; i++) {
            final int size = i;
            String[] names = {"Small", "Normal", "Large"};
            bubbleRow.addView(Ui.chip(this, names[i], prefs.bubbleSize() == i, v -> {
                prefs.setBubbleSize(size);
                push();
                refreshAll();
            }));
        }
        boardCard.addView(bubbleRow, Ui.lpTop(this, 0, 6));
        root.addView(boardCard, Ui.lpTop(this, 0, 14));

        // -------------------------------------------------- ABOUT
        LinearLayout about = Ui.card(this);
        about.addView(Ui.sectionTitle(this, "ABOUT"));
        about.addView(Ui.text(this, "Chess Hint Panel 1.1", 13.5f, Ui.TEXT, true));
        about.addView(Ui.text(this, "Stockfish 11 compiled for this phone (ARM64 / ARMv7 / x86_64).\n"
                + "Runs fully offline - the board is read from the screen, the move comes from your own device.\n\n"
                + "Strength: MAX is the strongest setting that exists in chess - about 3400+ Elo. "
                + "No engine on earth reaches 8000 Elo; the strongest ever measured are ~3600, so MAX is already at that ceiling.",
                12f, Ui.TEXT_DIM, false), Ui.lpTop(this, 0, 6));
        root.addView(about, Ui.lpTop(this, 0, 14));

        return sv;
    }

    // ---------------------------------------------------------------- actions

    private void call(int what) {
        switch (what) {
            case 0: OverlayService.requestCalibration(); toast("Drag the frame over the board"); break;
            case 1: OverlayService.requestAutoDetect(); toast("Looking for the board…"); break;
            case 2: OverlayService.requestEditor(); toast("Tap the squares to fix the pieces"); break;
            case 3: OverlayService.newGame(); toast("Position reset"); break;
        }
    }

    private void push() {
        OverlayService.settingsChanged();
        if (preview != null) preview.set(prefs.markerStyle(), prefs.palette(), prefs.markerScale(), prefs.showLabel());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void refreshAll() {
        if (preview != null) preview.set(prefs.markerStyle(), prefs.palette(), prefs.markerScale(), prefs.showLabel());

        // style chips
        styleRow.removeAllViews();
        String[] styleNames = {"Arrow + rings", "Arrow only", "Rings only", "Squares"};
        for (int i = 0; i < 4; i++) {
            final int s = i;
            styleRow.addView(Ui.chip(this, styleNames[i], prefs.markerStyle() == i, v -> {
                prefs.setMarkerStyle(s);
                push();
                refreshAll();
            }));
        }

        // palette chips
        paletteRow.removeAllViews();
        for (int i = 0; i < Markers.PALETTE.length; i++) {
            final int p = i;
            TextView t = Ui.chip(this, Markers.PALETTE_NAME[i], prefs.palette() == i, v -> {
                prefs.setPalette(p);
                push();
                refreshAll();
            });
            paletteRow.addView(t);
        }

        // size chips
        sizeRow.removeAllViews();
        String[] sizes = {"Small", "Normal", "Large"};
        for (int i = 0; i < 3; i++) {
            final int s = i;
            sizeRow.addView(Ui.chip(this, sizes[i], prefs.markerSize() == i, v -> {
                prefs.setMarkerSize(s);
                push();
                refreshAll();
            }));
        }

        // side
        sideRow.removeAllViews();
        sideRow.addView(Ui.chip(this, "White at bottom (me)", prefs.whiteBottom(), v -> {
            prefs.setWhiteBottom(true);
            push();
            refreshAll();
        }));
        sideRow.addView(Ui.chip(this, "Black at bottom (me)", !prefs.whiteBottom(), v -> {
            prefs.setWhiteBottom(false);
            push();
            refreshAll();
        }));

        // strength
        strengthRow.removeAllViews();
        int[] elos = {-1, 2850, 2400, 1800, 1400};
        String[] names = {"MAX", "2850", "2400", "1800", "1400"};
        for (int i = 0; i < elos.length; i++) {
            final int e = elos[i];
            strengthRow.addView(Ui.chip(this, names[i], prefs.elo() == e, v -> {
                prefs.setElo(e);
                push();
                refreshAll();
            }));
        }

        // time
        timeRow.removeAllViews();
        int[] times = {500, 1000, 1500, 3000, 5000};
        String[] tn = {"0.5s", "1s", "1.5s", "3s", "5s"};
        for (int i = 0; i < times.length; i++) {
            final int t = times[i];
            timeRow.addView(Ui.chip(this, tn[i], prefs.movetime() == t, v -> {
                prefs.setMovetime(t);
                push();
                refreshAll();
            }));
        }

        android.graphics.Rect r = prefs.boardRect();
        calibrationLabel.setText(r == null ? "Board frame: not set yet — use Fit frame or Auto detect."
                : "Board frame: " + r.left + ", " + r.top + "  size " + r.width() + "px");
    }
}
