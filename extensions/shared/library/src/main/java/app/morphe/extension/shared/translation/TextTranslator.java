/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.translation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Machine translation of plain text lines, using the public Google endpoint.
 *
 * <p>Lines are sent joined by newlines and come back in the same order, so the
 * caller can map them back one to one.
 */
public final class TextTranslator {

    private static final String[] TRANSLATE_ENDPOINTS = {
            "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=",
            "https://clients5.google.com/translate_a/t?client=at&sl=auto&tl=",
            "https://translate.googleapis.com/translate_a/single?client=at&sl=auto&dt=t&tl="
    };

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 10_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 15_000;

    /**
     * Batches are built by character budget rather than line count, so request
     * sizes stay uniform regardless of how long the lines are.
     */
    public static final int MAXIMUM_BATCH_CHARACTERS = 4_000;

    private TextTranslator() {
    }

    /**
     * Raised when the endpoint answers with anything other than 200, so callers can
     * tell a rate limit from a network failure and react to the status code.
     */
    public static final class TranslationHttpException extends Exception {
        public final int statusCode;

        public TranslationHttpException(int statusCode, @NonNull String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    /**
     * Splits lines into batches that each stay within {@link #MAXIMUM_BATCH_CHARACTERS}.
     * A single line longer than the budget is kept in a batch of its own.
     *
     * @param budget Maximum characters per batch.
     */
    @NonNull
    public static List<List<String>> splitByCharacterBudget(@NonNull List<String> lines, int budget) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentLength = 0;

        for (String line : lines) {
            final int length = line.length() + 1;
            if (!current.isEmpty() && currentLength + length > budget) {
                batches.add(current);
                current = new ArrayList<>();
                currentLength = 0;
            }
            current.add(line);
            currentLength += length;
        }

        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /**
     * Translates one batch of lines. Always call off the main thread.
     *
     * @param targetLanguage Language code such as {@code uk} or {@code en}.
     * @return Translated lines, in the order they were given.
     */
    @NonNull
    public static List<String> translate(@NonNull List<String> lines, @NonNull String targetLanguage)
            throws Exception {
        Utils.verifyOffMainThread();
        if (lines.isEmpty()) {
            return Collections.emptyList();
        }

        final long startTime = System.currentTimeMillis();

        // Normalize legacy language codes
        String lang = targetLanguage.toLowerCase(Locale.ROOT);
        if ("in".equals(lang)) {
            lang = "id";
        } else if ("iw".equals(lang)) {
            lang = "he";
        } else if ("ji".equals(lang)) {
            lang = "yi";
        }

        StringBuilder joined = new StringBuilder(100 * lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                joined.append('\n');
            }
            joined.append(lines.get(i));
        }

        byte[] body = ("q=" + URLEncoder.encode(joined.toString(), StandardCharsets.UTF_8.name()))
                .getBytes(StandardCharsets.UTF_8);

        Exception lastException = null;
        for (String endpoint : TRANSLATE_ENDPOINTS) {
            HttpURLConnection connection = null;
            try {
                connection = Requester.openConnection(endpoint + lang);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);

                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(body);
                    stream.flush();
                }

                int code = connection.getResponseCode();
                if (code != 200) {
                    throw new TranslationHttpException(code, "Translation HTTP " + code + " from " + endpoint);
                }

                String response = Requester.parseString(connection);
                if (response == null || response.isEmpty()) {
                    continue;
                }

                String translatedText = parseTranslationResponse(response);
                if (translatedText != null) {
                    Logger.printDebug(() -> "Translation complete via " + endpoint + ": " + targetLanguage
                            + " lines: " + lines.size()
                            + " fetchTime: " + (System.currentTimeMillis() - startTime) + "ms");
                    return Arrays.asList(translatedText.split("\n", -1));
                }
            } catch (Exception ex) {
                lastException = ex;
                Logger.printDebug(() -> "Translate endpoint failed: " + endpoint, ex);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("All translation endpoints failed");
    }

    @Nullable
    private static String parseTranslationResponse(String response) {
        try {
            JSONArray json = new JSONArray(response);
            if (json.length() == 0) return null;
            Object first = json.get(0);
            if (first instanceof JSONArray arr) {
                if (arr.length() == 0) return null;
                Object item = arr.get(0);
                if (item instanceof String str) {
                    return str;
                } else if (item instanceof JSONArray) {
                    // Nested sentences format: [[["trans1", "orig1"], ["trans2", "orig2"]]]
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < arr.length(); i++) {
                        Object sentObj = arr.get(i);
                        if (sentObj instanceof JSONArray sent) {
                            if (sent.length() > 0 && sent.get(0) instanceof String s) {
                                sb.append(s);
                            }
                        }
                    }
                    return sb.toString();
                }
            } else if (first instanceof String str) {
                return str;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Failed to parse translation JSON", ex);
        }
        return null;
    }
}
