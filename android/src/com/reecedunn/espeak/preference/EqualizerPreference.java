/*
 * Copyright (C) 2026 uyt3ar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reecedunn.espeak.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.DialogPreference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.reecedunn.espeak.AudioEqualizer;
import com.reecedunn.espeak.EspeakApp;
import com.reecedunn.espeak.R;
import com.reecedunn.espeak.SpeechSynthesis;

/**
 * Settings entry for the speech equalizer.
 *
 * The main dialog shows only the preset spinner. Choosing "Custom" opens a
 * second dialog with the five band sliders and a RESET button; the main
 * dialog never shows sliders itself.
 */
public class EqualizerPreference extends DialogPreference {
    private static final int[] PRESET_NAMES = {
        R.string.equalizer_preset_off,
        R.string.equalizer_preset_bass_boost,
        R.string.equalizer_preset_treble,
        R.string.equalizer_preset_vocal_bright,
        R.string.equalizer_preset_warm,
        R.string.equalizer_preset_custom,
    };

    /** SeekBar range: 0..3000 maps to -1500..+1500 mB in 100 mB (1 dB) steps. */
    private static final int SLIDER_MAX = AudioEqualizer.MAX_LEVEL_MB - AudioEqualizer.MIN_LEVEL_MB;
    private static final int SLIDER_ZERO = -AudioEqualizer.MIN_LEVEL_MB;

    private Spinner mPresetSpinner;
    private Button mEditCustomButton;
    /** Suppresses the spinner callback while we set the selection ourselves. */
    private boolean mSuppressSpinner;

    /** Sample rate used to label the sliders with the frequency really filtered. */
    private int mSampleRate = SpeechSynthesis.DEFAULT_SAMPLE_RATE;

    private String mPreset = AudioEqualizer.PRESET_OFF;
    /** Preset that was selected before the user picked Custom; restored on cancel. */
    private String mPresetBeforeCustom = AudioEqualizer.PRESET_OFF;
    private short[] mCustomLevels = new short[AudioEqualizer.BAND_COUNT];

    public EqualizerPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    public EqualizerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EqualizerPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setDialogLayoutResource(R.layout.equalizer_preference);
        setPositiveButtonText(android.R.string.ok);
        setNegativeButtonText(android.R.string.cancel);
        setKey(AudioEqualizer.PREF_PRESET);
        loadFromPreferences();
        updateSummary();
    }

    /**
     * Tells the dialog which sample rate the engine runs at so that band
     * labels reflect the effective (Nyquist-clamped) centre frequencies.
     */
    public void setSampleRate(int sampleRate) {
        if (sampleRate > 0) {
            mSampleRate = sampleRate;
        }
    }

    private SharedPreferences getPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(EspeakApp.getStorageContext());
    }

    private void loadFromPreferences() {
        SharedPreferences prefs = getPrefs();
        mPreset = prefs.getString(AudioEqualizer.PREF_PRESET, AudioEqualizer.PRESET_OFF);
        if (!AudioEqualizer.isValidPreset(mPreset)) {
            mPreset = AudioEqualizer.PRESET_OFF;
        }
        mPresetBeforeCustom = mPreset;
        mCustomLevels = AudioEqualizer.getCustomLevels(prefs);
    }

    public static String getPresetDisplayName(Context context, String preset) {
        for (int i = 0; i < AudioEqualizer.PRESET_VALUES.length; ++i) {
            if (AudioEqualizer.PRESET_VALUES[i].equals(preset)) {
                return context.getString(PRESET_NAMES[i]);
            }
        }
        return context.getString(PRESET_NAMES[0]);
    }

    private void updateSummary() {
        setSummary(getPresetDisplayName(getContext(), mPreset));
    }

    private static int presetIndex(String preset) {
        for (int i = 0; i < AudioEqualizer.PRESET_VALUES.length; ++i) {
            if (AudioEqualizer.PRESET_VALUES[i].equals(preset)) return i;
        }
        return 0;
    }

    private void selectPresetQuietly(String preset) {
        mSuppressSpinner = true;
        mPresetSpinner.setSelection(presetIndex(preset), false);
        mSuppressSpinner = false;
        updateEditButton();
    }

    private void updateEditButton() {
        if (mEditCustomButton != null) {
            mEditCustomButton.setVisibility(
                    AudioEqualizer.PRESET_CUSTOM.equals(mPreset) ? View.VISIBLE : View.GONE);
        }
    }

    // ---------------------------------------------------------------------
    // Main dialog: preset spinner only
    // ---------------------------------------------------------------------

    @Override
    protected View onCreateDialogView() {
        View root = super.onCreateDialogView();
        mPresetSpinner = root.findViewById(R.id.equalizer_preset);
        mEditCustomButton = root.findViewById(R.id.equalizer_edit_custom);

        String[] names = new String[PRESET_NAMES.length];
        for (int i = 0; i < names.length; ++i) {
            names[i] = getContext().getString(PRESET_NAMES[i]);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                getContext(), android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mPresetSpinner.setAdapter(adapter);

        mPresetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mSuppressSpinner) {
                    return;
                }
                String selected = AudioEqualizer.PRESET_VALUES[position];
                if (selected.equals(mPreset)) {
                    return;
                }
                if (AudioEqualizer.PRESET_CUSTOM.equals(selected)) {
                    // Remember where we came from so Cancel in the custom
                    // dialog can put the spinner back.
                    mPresetBeforeCustom = mPreset;
                    mPreset = selected;
                    updateEditButton();
                    showCustomDialog();
                } else {
                    mPreset = selected;
                    updateEditButton();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        mEditCustomButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPresetBeforeCustom = AudioEqualizer.PRESET_CUSTOM;
                showCustomDialog();
            }
        });

        return root;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        loadFromPreferences();
        selectPresetQuietly(mPreset);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (!positiveResult) {
            loadFromPreferences();
            return;
        }
        if (!callChangeListener(mPreset)) {
            return;
        }
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putString(AudioEqualizer.PREF_PRESET, mPreset);
        AudioEqualizer.putCustomLevels(editor, mCustomLevels);
        editor.apply();
        updateSummary();
    }

    // ---------------------------------------------------------------------
    // Custom dialog: five band sliders + RESET
    // ---------------------------------------------------------------------

    private void showCustomDialog() {
        final Context context = getContext();
        final View root = LayoutInflater.from(context).inflate(R.layout.equalizer_custom_dialog, null);
        final LinearLayout container = root.findViewById(R.id.equalizer_bands);

        final SeekBar[] sliders = new SeekBar[AudioEqualizer.BAND_COUNT];
        final TextView[] values = new TextView[AudioEqualizer.BAND_COUNT];
        // Working copy: only committed to mCustomLevels when the user taps OK.
        final short[] working = mCustomLevels.clone();

        for (int i = 0; i < AudioEqualizer.BAND_COUNT; ++i) {
            final int band = i;
            View row = LayoutInflater.from(context).inflate(R.layout.equalizer_band, container, false);
            TextView label = row.findViewById(R.id.band_label);
            label.setText(formatFrequency(AudioEqualizer.getEffectiveCenterHz(mSampleRate, i)));
            values[i] = row.findViewById(R.id.band_value);
            sliders[i] = row.findViewById(R.id.band_seekbar);
            sliders[i].setMax(SLIDER_MAX);
            sliders[i].setProgress(working[i] - AudioEqualizer.MIN_LEVEL_MB);
            values[i].setText(formatLevel(working[i]));
            sliders[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    short level = AudioEqualizer.clampLevel(
                            (progress / 100) * 100 + AudioEqualizer.MIN_LEVEL_MB);
                    working[band] = level;
                    values[band].setText(formatLevel(level));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });
            container.addView(row);
        }

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.equalizer_custom_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        mCustomLevels = working.clone();
                        mPreset = AudioEqualizer.PRESET_CUSTOM;
                        selectPresetQuietly(mPreset);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.equalizer_reset, null)
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        revertCustom();
                    }
                })
                .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                // Cancel button: go back to the previous preset.
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        revertCustom();
                        dialog.dismiss();
                    }
                });
                // RESET must keep the dialog open, so it needs its own listener
                // rather than the auto-dismissing one from the builder.
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        for (int i = 0; i < AudioEqualizer.BAND_COUNT; ++i) {
                            working[i] = 0;
                            sliders[i].setProgress(SLIDER_ZERO);
                            values[i].setText(formatLevel((short) 0));
                        }
                    }
                });
            }
        });
        dialog.show();
    }

    /** Cancel/back out of the custom dialog: restore the previous preset. */
    private void revertCustom() {
        mPreset = mPresetBeforeCustom;
        if (!AudioEqualizer.isValidPreset(mPreset)) {
            mPreset = AudioEqualizer.PRESET_OFF;
        }
        if (mPresetSpinner != null) {
            selectPresetQuietly(mPreset);
        }
    }

    private static String formatFrequency(int hz) {
        if (hz >= 1000) {
            return (hz % 1000 == 0) ? (hz / 1000) + " kHz"
                    : String.format(java.util.Locale.US, "%.1f kHz", hz / 1000.0);
        }
        return hz + " Hz";
    }

    private static String formatLevel(short millibel) {
        int db = millibel / 100;
        return (db > 0 ? "+" : "") + db + " dB";
    }
}
