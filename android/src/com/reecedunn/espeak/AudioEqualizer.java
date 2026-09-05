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

package com.reecedunn.espeak;

import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.util.Log;

/**
 * Five-band graphic equalizer for eSpeak's PCM output.
 *
 * The band layout (60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz centre
 * frequencies) and the gain range (-1500..+1500 millibel) mirror the
 * standard {@link android.media.audiofx.Equalizer} bands on Android so the
 * same preset values can be applied either way:
 *
 * <ul>
 *   <li>{@link #attach(int)} binds a real {@link Equalizer} effect to an
 *   audio session when a caller owns the playback {@code AudioTrack};</li>
 *   <li>{@link #process(byte[])} filters the 16-bit mono PCM samples in
 *   place. This is the path {@link TtsService} uses, because the TTS
 *   framework (not the engine) owns the output track, so no audio session
 *   is available to the engine.</li>
 * </ul>
 *
 * The in-engine path runs a cascade of peaking biquad filters (RBJ cookbook)
 * at the engine's native sample rate. With espeak-ng's fixed 22050 Hz output
 * the top band is clamped below Nyquist. Bands whose gain is 0 dB are skipped
 * entirely, and when the preset is {@link #PRESET_OFF} the buffer is returned
 * untouched, so the disabled state costs nothing.
 */
public class AudioEqualizer {
    private static final String TAG = AudioEqualizer.class.getSimpleName();

    public static final String PREF_PRESET = "espeak_equalizer_preset";
    public static final String PREF_CUSTOM_BAND_PREFIX = "espeak_equalizer_band_";

    public static final String PRESET_OFF = "off";
    public static final String PRESET_BASS_BOOST = "bass_boost";
    public static final String PRESET_TREBLE = "treble";
    public static final String PRESET_VOCAL_BRIGHT = "vocal_bright";
    public static final String PRESET_WARM = "warm";
    public static final String PRESET_CUSTOM = "custom";

    public static final String[] PRESET_VALUES = {
        PRESET_OFF, PRESET_BASS_BOOST, PRESET_TREBLE, PRESET_VOCAL_BRIGHT, PRESET_WARM, PRESET_CUSTOM,
    };

    /** Number of bands, matching the stock Android equalizer. */
    public static final int BAND_COUNT = 5;
    /**
     * Nominal band centre frequencies in Hz, same as android.media.audiofx.Equalizer.
     * The top band is only reachable at sample rates above 28 kHz; use
     * {@link #getEffectiveCenterHz(int, int)} for the frequency actually filtered.
     */
    public static final int[] BAND_CENTER_HZ = { 60, 230, 910, 3600, 14000 };
    /** Highest band centre as a fraction of Nyquist; keeps the biquad well-conditioned. */
    private static final double MAX_CENTER_FRACTION_OF_NYQUIST = 0.85;
    /** Gain range in millibels, as reported by Equalizer.getBandLevelRange(). */
    public static final short MIN_LEVEL_MB = -1500;
    public static final short MAX_LEVEL_MB = 1500;

    /** Band levels in millibels for each built-in preset (index order of PRESET_VALUES). */
    private static final short[][] PRESET_LEVELS = {
        /* off          */ {     0,     0,     0,     0,     0 },
        /* bass boost   */ {   900,   600,     0,  -100,  -200 },
        /* treble       */ {  -300,  -100,   100,   600,   800 },
        /* vocal bright */ {  -400,  -100,   400,   700,   300 },
        /* warm         */ {   400,   500,   100,  -300,  -500 },
        /* custom       */ {     0,     0,     0,     0,     0 },
    };

    /** Q factors per band. Wider on the low shelf-like bands, tighter mid/high. */
    private static final double[] BAND_Q = { 0.7, 0.9, 1.0, 1.0, 0.8 };

    private final int mSampleRate;
    private final Biquad[] mFilters = new Biquad[BAND_COUNT];
    private final short[] mLevels = new short[BAND_COUNT];
    private String mPreset = PRESET_OFF;
    private boolean mBypass = true;

    private Equalizer mSystemEqualizer;

    public AudioEqualizer(int sampleRate) {
        mSampleRate = sampleRate;
        for (int i = 0; i < BAND_COUNT; ++i) {
            mFilters[i] = new Biquad();
        }
        rebuildFilters();
    }

    // ---------------------------------------------------------------------
    // Presets & levels
    // ---------------------------------------------------------------------

    public static short[] getPresetLevels(String preset) {
        for (int i = 0; i < PRESET_VALUES.length; ++i) {
            if (PRESET_VALUES[i].equals(preset)) {
                return PRESET_LEVELS[i].clone();
            }
        }
        return PRESET_LEVELS[0].clone();
    }

    public static boolean isValidPreset(String preset) {
        for (String p : PRESET_VALUES) {
            if (p.equals(preset)) return true;
        }
        return false;
    }

    /**
     * Centre frequency the given band really operates at for a sample rate.
     * Bands above ~85% of Nyquist are pulled down to that limit, so at
     * espeak-ng's 22050 Hz the nominal 14 kHz band becomes ~9.4 kHz.
     */
    public static int getEffectiveCenterHz(int sampleRate, int band) {
        final double limit = (sampleRate / 2.0) * MAX_CENTER_FRACTION_OF_NYQUIST;
        return (int) Math.round(Math.min(BAND_CENTER_HZ[band], limit));
    }

    public static short clampLevel(int millibel) {
        if (millibel < MIN_LEVEL_MB) return MIN_LEVEL_MB;
        if (millibel > MAX_LEVEL_MB) return MAX_LEVEL_MB;
        return (short) millibel;
    }

    /** Reads the custom band levels the user stored from the slider UI. */
    public static short[] getCustomLevels(SharedPreferences prefs) {
        short[] levels = new short[BAND_COUNT];
        for (int i = 0; i < BAND_COUNT; ++i) {
            levels[i] = clampLevel(prefs.getInt(PREF_CUSTOM_BAND_PREFIX + i, 0));
        }
        return levels;
    }

    public static void putCustomLevels(SharedPreferences.Editor editor, short[] levels) {
        for (int i = 0; i < BAND_COUNT && i < levels.length; ++i) {
            editor.putInt(PREF_CUSTOM_BAND_PREFIX + i, clampLevel(levels[i]));
        }
    }

    /** Resolves the effective band levels for the preferences as currently saved. */
    public static short[] resolveLevels(SharedPreferences prefs) {
        String preset = prefs.getString(PREF_PRESET, PRESET_OFF);
        if (PRESET_CUSTOM.equals(preset)) {
            return getCustomLevels(prefs);
        }
        return getPresetLevels(preset);
    }

    public String getPreset() {
        return mPreset;
    }

    public short[] getLevels() {
        return mLevels.clone();
    }

    /** True when every band is flat, i.e. process() is a no-op. */
    public boolean isBypassed() {
        return mBypass;
    }

    /**
     * Loads preset + custom levels from preferences. Cheap when nothing
     * changed, so it is safe to call once per synthesis request.
     */
    public void applyPreferences(SharedPreferences prefs) {
        String preset = prefs.getString(PREF_PRESET, PRESET_OFF);
        if (!isValidPreset(preset)) {
            preset = PRESET_OFF;
        }
        short[] levels = PRESET_CUSTOM.equals(preset) ? getCustomLevels(prefs) : getPresetLevels(preset);
        setLevels(preset, levels);
    }

    public void setPreset(String preset) {
        if (!isValidPreset(preset) || PRESET_CUSTOM.equals(preset)) {
            return;
        }
        setLevels(preset, getPresetLevels(preset));
    }

    public void setCustomLevels(short[] levels) {
        setLevels(PRESET_CUSTOM, levels);
    }

    private void setLevels(String preset, short[] levels) {
        boolean changed = !preset.equals(mPreset);
        for (int i = 0; i < BAND_COUNT; ++i) {
            short v = i < levels.length ? clampLevel(levels[i]) : 0;
            if (v != mLevels[i]) {
                mLevels[i] = v;
                changed = true;
            }
        }
        mPreset = preset;
        if (changed) {
            rebuildFilters();
            pushToSystemEqualizer();
        }
    }

    private void rebuildFilters() {
        boolean bypass = true;
        final double nyquist = mSampleRate / 2.0;
        for (int i = 0; i < BAND_COUNT; ++i) {
            double gainDb = mLevels[i] / 100.0;
            if (mLevels[i] == 0) {
                mFilters[i].setIdentity();
                continue;
            }
            bypass = false;
            // Keep the centre frequency safely below Nyquist; at 22050 Hz the
            // nominal 14 kHz band becomes ~9.4 kHz (see getEffectiveCenterHz).
            double fc = Math.min(BAND_CENTER_HZ[i], nyquist * MAX_CENTER_FRACTION_OF_NYQUIST);
            mFilters[i].setPeaking(mSampleRate, fc, BAND_Q[i], gainDb);
        }
        mBypass = bypass;
    }

    // ---------------------------------------------------------------------
    // In-engine PCM processing (16-bit little-endian mono)
    // ---------------------------------------------------------------------

    /**
     * Filters a buffer of signed 16-bit little-endian mono PCM in place.
     * Returns the same array for convenience. Filter state carries across
     * calls, so consecutive buffers of one utterance are processed seamlessly.
     */
    public byte[] process(byte[] pcm) {
        if (mBypass || pcm == null) {
            return pcm;
        }
        final int frames = pcm.length / 2;
        for (int n = 0; n < frames; ++n) {
            final int idx = n * 2;
            double x = (short) ((pcm[idx] & 0xff) | (pcm[idx + 1] << 8));
            for (int b = 0; b < BAND_COUNT; ++b) {
                if (mLevels[b] != 0) {
                    x = mFilters[b].tick(x);
                }
            }
            int y = (int) Math.round(x);
            if (y > Short.MAX_VALUE) y = Short.MAX_VALUE;
            else if (y < Short.MIN_VALUE) y = Short.MIN_VALUE;
            pcm[idx] = (byte) (y & 0xff);
            pcm[idx + 1] = (byte) ((y >> 8) & 0xff);
        }
        return pcm;
    }

    /** Clears filter history. Call between utterances to avoid tails bleeding over. */
    public void reset() {
        for (Biquad f : mFilters) {
            f.reset();
        }
    }

    // ---------------------------------------------------------------------
    // Optional binding to android.media.audiofx.Equalizer
    // ---------------------------------------------------------------------

    /**
     * Attaches a system {@link Equalizer} effect to the given audio session
     * and mirrors the current band levels onto it. Use this when the caller
     * owns the output {@code AudioTrack} (e.g. a preview player); the TTS
     * framework's own track is not reachable from the engine.
     *
     * @return true if the effect could be created.
     */
    public boolean attach(int audioSessionId) {
        release();
        try {
            mSystemEqualizer = new Equalizer(0, audioSessionId);
            pushToSystemEqualizer();
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "System Equalizer unavailable for session " + audioSessionId, e);
            mSystemEqualizer = null;
            return false;
        }
    }

    public void release() {
        if (mSystemEqualizer != null) {
            try {
                mSystemEqualizer.release();
            } catch (RuntimeException ignored) {
            }
            mSystemEqualizer = null;
        }
    }

    private void pushToSystemEqualizer() {
        final Equalizer eq = mSystemEqualizer;
        if (eq == null) {
            return;
        }
        try {
            eq.setEnabled(!mBypass);
            final short bands = eq.getNumberOfBands();
            final short[] range = eq.getBandLevelRange();
            for (short band = 0; band < bands; ++band) {
                // Map our band to the nearest system band by centre frequency.
                int centreHz = eq.getCenterFreq(band) / 1000;
                int nearest = 0;
                for (int i = 1; i < BAND_COUNT; ++i) {
                    if (Math.abs(BAND_CENTER_HZ[i] - centreHz) < Math.abs(BAND_CENTER_HZ[nearest] - centreHz)) {
                        nearest = i;
                    }
                }
                int level = mLevels[nearest];
                if (level < range[0]) level = range[0];
                if (level > range[1]) level = range[1];
                eq.setBandLevel(band, (short) level);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to update system Equalizer", e);
        }
    }

    // ---------------------------------------------------------------------
    // RBJ peaking-EQ biquad, direct form I
    // ---------------------------------------------------------------------

    static final class Biquad {
        private double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        private double x1, x2, y1, y2;

        void setIdentity() {
            b0 = 1; b1 = 0; b2 = 0; a1 = 0; a2 = 0;
            reset();
        }

        void setPeaking(double sampleRate, double fc, double q, double gainDb) {
            final double A = Math.pow(10.0, gainDb / 40.0);
            final double w0 = 2.0 * Math.PI * fc / sampleRate;
            final double alpha = Math.sin(w0) / (2.0 * q);
            final double cosw0 = Math.cos(w0);

            final double a0 = 1 + alpha / A;
            b0 = (1 + alpha * A) / a0;
            b1 = (-2 * cosw0) / a0;
            b2 = (1 - alpha * A) / a0;
            a1 = (-2 * cosw0) / a0;
            a2 = (1 - alpha / A) / a0;
            reset();
        }

        double tick(double x) {
            final double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x;
            y2 = y1; y1 = y;
            return y;
        }

        void reset() {
            x1 = x2 = y1 = y2 = 0;
        }
    }
}
