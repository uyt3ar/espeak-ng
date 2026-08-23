/*
 * Copyright (C) 2026 eSpeak NG contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.reecedunn.espeak;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Imports compiled dictionaries with the Storage Access Framework and lets a
 * user edit and compile the corresponding dictionary list on the device.
 *
 * ACTION_OPEN_DOCUMENT is deliberately used instead of direct external-storage
 * paths. Direct access stopped working with scoped storage on Android 11, while
 * a content URI grants access to a user-selected file on local or cloud storage
 * without broad storage permissions.
 */
public class DictionaryManagerActivity extends Activity {
    private static final int REQUEST_IMPORT_DICTIONARY = 1;
    private static final long MAX_DICTIONARY_BYTES = 64L * 1024L * 1024L;
    private static final int N_HASH_DICT = 1024;
    private static final Pattern DICTIONARY_FILE =
            Pattern.compile("[A-Za-z0-9_-]+_dict");
    private static final String[] SOURCE_SUFFIXES = {
            "rules", "roots", "listx", "emoji", "extra"
    };

    private Spinner mDictionarySelector;
    private EditText mDictionarySource;
    private TextView mStatus;
    private Button mSaveButton;
    private ArrayAdapter<String> mDictionaryAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.dictionary_manager_title);
        setContentView(R.layout.dictionary_manager);

        mDictionarySelector = findViewById(R.id.dictionary_selector);
        mDictionarySource = findViewById(R.id.dictionary_source);
        mStatus = findViewById(R.id.dictionary_status);
        mSaveButton = findViewById(R.id.save_dictionary);

        mDictionaryAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, new ArrayList<String>());
        mDictionaryAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mDictionarySelector.setAdapter(mDictionaryAdapter);
        mDictionarySelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                loadSelectedDictionarySource();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                setEditorAvailable(false, getString(R.string.dictionary_none_found));
            }
        });

        findViewById(R.id.import_dictionary).setOnClickListener(v -> openDictionaryPicker());
        mSaveButton.setOnClickListener(v -> saveAndCompile());
        refreshDictionaryList(null);
    }

    private void openDictionaryPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/octet-stream", "application/x-binary", "*/*"
        });
        startActivityForResult(intent, REQUEST_IMPORT_DICTIONARY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_DICTIONARY || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }

        final Uri uri = data.getData();
        final String displayName = getDisplayName(uri);
        if (displayName == null || !DICTIONARY_FILE.matcher(displayName).matches()) {
            showMessage(R.string.dictionary_invalid_name);
            return;
        }

        setBusy(true, getString(R.string.dictionary_importing));
        new Thread(() -> {
            String error = null;
            try {
                importDictionary(uri, displayName);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String importError = error;
            runOnUiThread(() -> {
                setBusy(false, importError == null
                        ? getString(R.string.dictionary_import_success, displayName)
                        : getString(R.string.dictionary_import_failed, importError));
                if (importError == null) {
                    refreshDictionaryList(displayName.substring(
                            0, displayName.length() - "_dict".length()));
                    sendLanguagesUpdatedBroadcast();
                }
            });
        }, "dictionary-import").start();
    }

    private void importDictionary(Uri uri, String displayName) throws IOException {
        ContentResolver resolver = getContentResolver();
        File dataPath = CheckVoiceData.getDataPath(EspeakApp.getStorageContext());
        if (!dataPath.exists() && !dataPath.mkdirs()) {
            throw new IOException(getString(R.string.dictionary_create_directory_failed));
        }

        File destination = new File(dataPath, displayName);
        File temporary = new File(dataPath, displayName + ".importing");
        long total = 0;
        byte[] header = new byte[4];
        int headerLength = 0;

        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (input == null) {
                throw new IOException(getString(R.string.dictionary_open_failed));
            }
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (headerLength < header.length) {
                    int copy = Math.min(count, header.length - headerLength);
                    System.arraycopy(buffer, 0, header, headerLength, copy);
                    headerLength += copy;
                }
                total += count;
                if (total > MAX_DICTIONARY_BYTES) {
                    throw new IOException(getString(R.string.dictionary_too_large));
                }
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        } catch (IOException e) {
            temporary.delete();
            throw e;
        }

        int hashCount = (header[0] & 0xff)
                | ((header[1] & 0xff) << 8)
                | ((header[2] & 0xff) << 16)
                | ((header[3] & 0xff) << 24);
        if (headerLength != 4 || total <= 8 || hashCount != N_HASH_DICT) {
            temporary.delete();
            throw new IOException(getString(R.string.dictionary_invalid_file));
        }

        File backup = new File(dataPath, displayName + ".bak");
        backup.delete();
        if (destination.exists() && !destination.renameTo(backup)) {
            temporary.delete();
            throw new IOException(getString(R.string.dictionary_replace_failed));
        }
        if (!temporary.renameTo(destination)) {
            if (backup.exists()) backup.renameTo(destination);
            temporary.delete();
            throw new IOException(getString(R.string.dictionary_replace_failed));
        }
        backup.delete();
    }

    private void refreshDictionaryList(String selectName) {
        File dataPath = CheckVoiceData.getDataPath(EspeakApp.getStorageContext());
        File[] files = dataPath.listFiles(file -> file.isFile()
                && DICTIONARY_FILE.matcher(file.getName()).matches());
        List<String> dictionaries = new ArrayList<String>();
        if (files != null) {
            for (File file : files) {
                String filename = file.getName();
                dictionaries.add(filename.substring(0,
                        filename.length() - "_dict".length()));
            }
        }
        Collections.sort(dictionaries);
        mDictionaryAdapter.clear();
        mDictionaryAdapter.addAll(dictionaries);
        mDictionaryAdapter.notifyDataSetChanged();

        if (dictionaries.isEmpty()) {
            setEditorAvailable(false, getString(R.string.dictionary_none_found));
            return;
        }
        if (selectName != null) {
            int position = dictionaries.indexOf(selectName);
            if (position >= 0) mDictionarySelector.setSelection(position);
        }
    }

    private void loadSelectedDictionarySource() {
        final String name = getSelectedDictionary();
        if (name == null) return;
        setBusy(true, getString(R.string.dictionary_loading_source));
        new Thread(() -> {
            String contents = null;
            String error = null;
            try {
                if (!assetExists(name + "_rules")) {
                    throw new IOException(getString(R.string.dictionary_source_unavailable));
                }
                File list = getEditableListFile(name);
                if (!list.exists()) {
                    copyAssetIfPresent(name + "_list", list);
                    if (!list.exists()) FileUtils.write(list, "");
                }
                contents = readUtf8(list);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String source = contents;
            final String loadError = error;
            runOnUiThread(() -> {
                setBusy(false, loadError == null
                        ? getString(R.string.dictionary_editor_ready, name)
                        : loadError);
                mDictionarySource.setText(source == null ? "" : source);
                mDictionarySource.setEnabled(loadError == null);
                mSaveButton.setEnabled(loadError == null);
            });
        }, "dictionary-source-load").start();
    }

    private void saveAndCompile() {
        final String name = getSelectedDictionary();
        if (name == null) return;
        final String source = mDictionarySource.getText().toString();
        setBusy(true, getString(R.string.dictionary_compiling));

        new Thread(() -> {
            String error = null;
            try {
                File sourceDir = getSourceDirectory();
                if (!sourceDir.exists() && !sourceDir.mkdirs()) {
                    throw new IOException(getString(R.string.dictionary_create_directory_failed));
                }
                copyAssetRequired(name + "_rules", new File(sourceDir, name + "_rules"));
                for (String suffix : SOURCE_SUFFIXES) {
                    if (!"rules".equals(suffix)) {
                        copyAssetIfPresent(name + "_" + suffix,
                                new File(sourceDir, name + "_" + suffix));
                    }
                }
                writeUtf8Atomically(getEditableListFile(name), source);
                int status = SpeechSynthesis.compileDictionary(
                        EspeakApp.getStorageContext(), sourceDir, name);
                if (status != 0) {
                    throw new IOException(getString(
                            R.string.dictionary_compile_status, status));
                }
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String compileError = error;
            runOnUiThread(() -> {
                setBusy(false, compileError == null
                        ? getString(R.string.dictionary_compile_success, name)
                        : getString(R.string.dictionary_compile_failed, compileError));
                if (compileError == null) sendLanguagesUpdatedBroadcast();
            });
        }, "dictionary-compile").start();
    }

    private File getSourceDirectory() {
        return new File(getFilesDir(), "dictionary-sources");
    }

    private File getEditableListFile(String name) {
        return new File(getSourceDirectory(), name + "_list");
    }

    private boolean assetExists(String name) {
        try (InputStream ignored = getAssets().open(name)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void copyAssetRequired(String assetName, File destination) throws IOException {
        if (!copyAssetIfPresent(assetName, destination)) {
            throw new IOException(getString(R.string.dictionary_source_unavailable));
        }
    }

    private boolean copyAssetIfPresent(String assetName, File destination) throws IOException {
        try (InputStream input = getAssets().open(assetName)) {
            if (destination.getParentFile() != null) destination.getParentFile().mkdirs();
            try (FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            return true;
        } catch (java.io.FileNotFoundException e) {
            return false;
        }
    }

    private static String readUtf8(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeUtf8Atomically(File destination, String contents)
            throws IOException {
        File temporary = new File(destination.getParentFile(),
                destination.getName() + ".saving");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Unable to replace dictionary source");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Unable to save dictionary source");
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (RuntimeException ignored) {
        }
        return uri.getLastPathSegment();
    }

    private String getSelectedDictionary() {
        Object selected = mDictionarySelector.getSelectedItem();
        return selected == null ? null : selected.toString();
    }

    private void setBusy(boolean busy, String status) {
        mStatus.setText(status);
        mDictionarySelector.setEnabled(!busy);
        findViewById(R.id.import_dictionary).setEnabled(!busy);
        mDictionarySource.setEnabled(!busy);
        mSaveButton.setEnabled(!busy);
    }

    private void setEditorAvailable(boolean available, String status) {
        mStatus.setText(status);
        mDictionarySource.setEnabled(available);
        mSaveButton.setEnabled(available);
    }

    private void showMessage(int stringResource) {
        String message = getString(stringResource);
        mStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void sendLanguagesUpdatedBroadcast() {
        sendBroadcast(new Intent(DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED));
    }
}
