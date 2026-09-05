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

package com.reecedunn.espeak.test;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.reecedunn.espeak.AudioEqualizer;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@RunWith(AndroidJUnit4.class)
public class AudioEqualizerTest {
    private static final int SAMPLE_RATE = 22050;

    private static byte[] sine(double hz, int frames, double amplitude) {
        byte[] pcm = new byte[frames * 2];
        for (int n = 0; n < frames; ++n) {
            short v = (short) Math.round(amplitude * Math.sin(2 * Math.PI * hz * n / SAMPLE_RATE));
            pcm[n * 2] = (byte) (v & 0xff);
            pcm[n * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        return pcm;
    }

    private static double rms(byte[] pcm, int fromFrame) {
        double acc = 0; int count = 0;
        for (int n = fromFrame; n < pcm.length / 2; ++n) {
            short v = (short) ((pcm[n * 2] & 0xff) | (pcm[n * 2 + 1] << 8));
            acc += (double) v * v; count++;
        }
        return Math.sqrt(acc / count);
    }

    @Test
    public void offPresetLeavesAudioUntouched() {
        AudioEqualizer eq = new AudioEqualizer(SAMPLE_RATE);
        eq.setPreset(AudioEqualizer.PRESET_OFF);
        byte[] in = sine(440, 4410, 8000);
        byte[] copy = in.clone();
        assertThat(eq.isBypassed(), is(true));
        eq.process(in);
        assertThat(in, is(copy));
    }

    @Test
    public void bassBoostRaisesLowAndLowersHigh() {
        AudioEqualizer eq = new AudioEqualizer(SAMPLE_RATE);
        eq.setPreset(AudioEqualizer.PRESET_BASS_BOOST);
        assertThat(eq.isBypassed(), is(false));

        byte[] low = sine(60, 22050, 6000);
        double before = rms(low, 11025);
        eq.process(low);
        assertThat(rms(low, 11025), greaterThan(before * 1.5));

        eq.reset();
        byte[] high = sine(8000, 22050, 6000);
        before = rms(high, 11025);
        eq.process(high);
        assertThat(rms(high, 11025), lessThan(before));
    }

    @Test
    public void trebleRaisesHigh() {
        AudioEqualizer eq = new AudioEqualizer(SAMPLE_RATE);
        eq.setPreset(AudioEqualizer.PRESET_TREBLE);
        byte[] high = sine(3600, 22050, 4000);
        double before = rms(high, 11025);
        eq.process(high);
        assertThat(rms(high, 11025), greaterThan(before * 1.4));
    }

    @Test
    public void customLevelsAreClampedAndApplied() {
        AudioEqualizer eq = new AudioEqualizer(SAMPLE_RATE);
        eq.setCustomLevels(new short[] { 5000, 0, 0, 0, -5000 });
        short[] levels = eq.getLevels();
        assertThat(levels[0], is(AudioEqualizer.MAX_LEVEL_MB));
        assertThat(levels[4], is(AudioEqualizer.MIN_LEVEL_MB));
        assertThat(eq.getPreset(), is(AudioEqualizer.PRESET_CUSTOM));
        assertThat(eq.isBypassed(), is(false));
    }

    @Test
    public void topBandIsClampedBelowNyquist() {
        // Nominal 14 kHz cannot exist at 22050 Hz; it must land under Nyquist.
        int top = AudioEqualizer.getEffectiveCenterHz(SAMPLE_RATE, AudioEqualizer.BAND_COUNT - 1);
        assertThat(top, lessThan(SAMPLE_RATE / 2));
        assertThat(top, greaterThan(8000));
        // Lower bands are untouched.
        assertThat(AudioEqualizer.getEffectiveCenterHz(SAMPLE_RATE, 0), is(60));
        assertThat(AudioEqualizer.getEffectiveCenterHz(SAMPLE_RATE, 3), is(3600));
        // At 48 kHz the nominal value is reachable.
        assertThat(AudioEqualizer.getEffectiveCenterHz(48000, AudioEqualizer.BAND_COUNT - 1), is(14000));
    }

    @Test
    public void outputNeverOverflows() {
        AudioEqualizer eq = new AudioEqualizer(SAMPLE_RATE);
        eq.setCustomLevels(new short[] { 1500, 1500, 1500, 1500, 1500 });
        byte[] loud = sine(230, 22050, 32000);
        eq.process(loud); // must not throw; values are clipped to 16-bit
        assertThat(loud.length, is(44100));
    }
}
