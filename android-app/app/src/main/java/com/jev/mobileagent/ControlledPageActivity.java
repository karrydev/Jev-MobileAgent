package com.jev.mobileagent;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A deterministic, fully accessible page used only for emulator evidence. */
public class ControlledPageActivity extends Activity {
    private TextView state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Controlled observation page");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(32));

        TextView heading = new TextView(this);
        heading.setText("Controlled observation page");
        heading.setTextSize(24);
        heading.setTextColor(Color.rgb(35, 50, 65));
        heading.setContentDescription("Controlled page heading");
        root.addView(heading, params());

        TextView text = new TextView(this);
        text.setText("Visible controlled text");
        text.setTextSize(18);
        text.setContentDescription("The tree must contain this visible text");
        root.addView(text, params());

        state = new TextView(this);
        state.setText("Controlled action state: ready");
        state.setTextSize(16);
        state.setContentDescription("Controlled action state ready");
        root.addView(state, params());

        Button action = new Button(this);
        action.setText("Toggle controlled state");
        action.setContentDescription("Toggle controlled state button");
        action.setOnClickListener(v -> {
            String next = state.getText().toString().endsWith("ready") ? "completed" : "ready";
            state.setText("Controlled action state: " + next);
            state.setContentDescription("Controlled action state " + next);
        });
        root.addView(action, params());
        setContentView(root);
    }

    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
