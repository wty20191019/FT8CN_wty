package com.bg7yoz.ft8cn.ui;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.database.DatabaseOpr;

/**
 * 并行解码参数设置。只影响 decodeMode=PARALLEL 时的行为，
 * 不改变旧快速/深度模式的任何参数。
 */
public class ParallelDecodeDialog {
    private static final int[] SLICES = {2, 3, 4, 6, 8};
    private static final int[] THREADS = {0, 1, 2, 3, 4};
    private static final int[] TIMEOUTS = {4000, 6000, 7000, 8000, 10000};

    private final Context context;
    private final DatabaseOpr databaseOpr;

    public ParallelDecodeDialog(Context context, DatabaseOpr databaseOpr) {
        this.context = context;
        this.databaseOpr = databaseOpr;
    }

    public void show() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        Spinner sliceSpinner = addSpinner(root, context.getString(R.string.parallel_slices));
        ArrayAdapter<String> sliceAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item);
        sliceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        for (int v : SLICES) sliceAdapter.add(String.valueOf(v));
        sliceSpinner.setAdapter(sliceAdapter);
        sliceSpinner.setSelection(indexOf(SLICES, GeneralVariables.parallelSlices));

        Spinner threadSpinner = addSpinner(root, context.getString(R.string.parallel_threads));
        ArrayAdapter<String> threadAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item);
        threadAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        for (int v : THREADS) {
            threadAdapter.add(v == 0 ? context.getString(R.string.parallel_auto) : String.valueOf(v));
        }
        threadSpinner.setAdapter(threadAdapter);
        threadSpinner.setSelection(indexOf(THREADS, GeneralVariables.parallelThreads));

        Spinner timeoutSpinner = addSpinner(root, context.getString(R.string.parallel_timeout));
        ArrayAdapter<String> timeoutAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item);
        timeoutAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        for (int v : TIMEOUTS) {
            timeoutAdapter.add(context.getString(R.string.parallel_seconds, v / 1000));
        }
        timeoutSpinner.setAdapter(timeoutAdapter);
        timeoutSpinner.setSelection(indexOf(TIMEOUTS, GeneralVariables.parallelTimeout));

        new AlertDialog.Builder(context)
                .setTitle(R.string.parallel_settings)
                .setView(root)
                .setPositiveButton(R.string.parallel_confirm, (dialog, which) -> {
                    GeneralVariables.parallelSlices = SLICES[sliceSpinner.getSelectedItemPosition()];
                    GeneralVariables.parallelThreads = THREADS[threadSpinner.getSelectedItemPosition()];
                    GeneralVariables.parallelTimeout = TIMEOUTS[timeoutSpinner.getSelectedItemPosition()];
                    if (databaseOpr != null) {
                        databaseOpr.writeConfig("parallelSlices",
                                String.valueOf(GeneralVariables.parallelSlices), null);
                        databaseOpr.writeConfig("parallelThreads",
                                String.valueOf(GeneralVariables.parallelThreads), null);
                        databaseOpr.writeConfig("parallelTimeout",
                                String.valueOf(GeneralVariables.parallelTimeout), null);
                    }
                })
                .setNegativeButton(R.string.parallel_cancel, null)
                .show();
    }

    private Spinner addSpinner(LinearLayout root, String label) {
        TextView text = new TextView(context);
        text.setText(label);
        int pad = (int) (4 * context.getResources().getDisplayMetrics().density);
        text.setPadding(0, pad, 0, 0);
        root.addView(text);

        Spinner spinner = new Spinner(context);
        spinner.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(spinner);
        return spinner;
    }

    private int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) return i;
        }
        return values.length / 2;
    }
}
