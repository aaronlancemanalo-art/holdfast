package com.aaron.holdfast;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The screen you land on when something is blocked. */
public class BlockActivity extends Activity {

    public static final String EXTRA_REASON = "reason";

    private TextView reasonView;
    private TextView countView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(32);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(pad, pad, pad, pad);

        TextView big = new TextView(this);
        big.setText("Not now.");
        big.setTextSize(40);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        box.addView(big);

        reasonView = new TextView(this);
        reasonView.setTextSize(18);
        reasonView.setGravity(Gravity.CENTER);
        reasonView.setPadding(0, dp(16), 0, dp(8));
        box.addView(reasonView);

        countView = new TextView(this);
        countView.setTextSize(14);
        countView.setGravity(Gravity.CENTER);
        countView.setAlpha(0.7f);
        box.addView(countView);

        TextView ask = new TextView(this);
        ask.setText("What were you about to avoid?");
        ask.setTextSize(16);
        ask.setGravity(Gravity.CENTER);
        ask.setPadding(0, dp(32), 0, dp(24));
        box.addView(ask);

        Button ok = new Button(this);
        ok.setText("Back to something useful");
        ok.setOnClickListener(v -> finish());
        box.addView(ok);

        setContentView(box);
        bind(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        bind(intent);
    }

    private void bind(Intent intent) {
        String reason = intent == null ? null : intent.getStringExtra(EXTRA_REASON);
        reasonView.setText(reason == null ? "This is blocked." : reason);
        int n = new Rules(this).blocksToday();
        countView.setText("Blocked " + n + (n == 1 ? " time" : " times") + " today");
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
