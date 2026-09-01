/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * Fetches lyrics directly from YouTube Music / YouTube InnerTube API (LyricFind, Musixmatch,
 * timed lyrics, or video closed captions) for tracks not found on third-party databases.
 */
public final class YouTubeMusicProvider implements LyricsProvider {

    private static final String NEXT_URL = "https://music.youtube.com/youtubei/v1/next";
    private static final String BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse";
    private static final String PLAYER_URL = "https://www.youtube.com/youtubei/v1/player";
    private static final String SEARCH_URL = "https://music.youtube.com/youtubei/v1/search";

    private static final int TIMEOUT_MS = 10 * 1000;

    private static final Pattern CAPTION_PATTERN =
            Pattern.compile("<text start=\"([0-9.]+)\"[^>]*>([^<]+)</text>");
    private static final Pattern VIDEO_ID_PATTERN =
            Pattern.compile("\"videoId\":\\s*\"([a-zA-Z0-9_-]{11})\"");

    @Override
    public String name() {
        return "YouTube Music";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty()) {
            videoId = searchVideoId(track);
        }
        if (videoId == null || videoId.isEmpty()) {
            return null;
        }

        // 1. Try YouTube Music InnerTube Next -> Browse (LyricFind / Musixmatch / Timed Lyrics)
        Lyrics lyrics = fetchFromInnerTubeBrowse(videoId);
        if (lyrics != null && !lyrics.isEmpty()) {
            return lyrics;
        }

        // 2. Try YouTube Video Embedded Closed Captions
        lyrics = fetchFromVideoCaptions(videoId);
        if (lyrics != null && !lyrics.isEmpty()) {
            return lyrics;
        }

        return null;
    }

    @Nullable
    private Lyrics fetchFromInnerTubeBrowse(String videoId) {
        try {
            JSONObject nextPayload = new JSONObject();
            nextPayload.put("videoId", videoId);
            JSONObject context = new JSONObject();
            JSONObject client = new JSONObject();
            client.put("clientName", "ANDROID_MUSIC");
            client.put("clientVersion", "6.43.52");
            client.put("hl", "ja");
            context.put("client", client);
            nextPayload.put("context", context);

            JSONObject nextResponse = postJson(NEXT_URL, nextPayload);
            if (nextResponse == null) {
                return null;
            }

            String browseId = extractLyricsBrowseId(nextResponse);
            if (browseId == null || browseId.isEmpty()) {
                return null;
            }

            JSONObject browsePayload = new JSONObject();
            browsePayload.put("browseId", browseId);
            browsePayload.put("context", context);

            JSONObject browseResponse = postJson(BROWSE_URL, browsePayload);
            if (browseResponse == null) {
                return null;
            }

            // Case A: Timed Lyrics
            JSONArray timedLines = findArray(browseResponse, "timedLyricsData");
            if (timedLines != null && timedLines.length() > 0) {
                List<LyricsLine> lines = new ArrayList<>(timedLines.length());
                for (int i = 0; i < timedLines.length(); i++) {
                    JSONObject item = timedLines.optJSONObject(i);
                    if (item == null) continue;
                    String text = item.optString("lyricLine", "").trim();
                    if (text.isEmpty() || "♪".equals(text)) {
                        continue;
                    }
                    JSONObject cueRange = item.optJSONObject("cueRange");
                    long startMs = -1;
                    if (cueRange != null) {
                        startMs = cueRange.optLong("startTimeMilliseconds", -1);
                        if (startMs < 0) {
                            try {
                                startMs = Long.parseLong(cueRange.optString("startTimeMilliseconds", "-1"));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    lines.add(new LyricsLine(startMs, text));
                }
                if (!lines.isEmpty()) {
                    String sourceName = "YouTube Music";
                    JSONObject footer = findObject(browseResponse, "footer");
                    if (footer != null) {
                        JSONArray footerRuns = footer.optJSONArray("runs");
                        if (footerRuns != null && footerRuns.length() > 0) {
                            String footerText = footerRuns.optJSONObject(0).optString("text", "");
                            if (!footerText.isEmpty()) {
                                sourceName = footerText;
                            }
                        }
                    }
                    return new Lyrics(lines, sourceName, true);
                }
            }

            // Case B: Static Plain Lyrics (from LyricFind / Musixmatch)
            JSONObject shelf = findObject(browseResponse, "musicDescriptionShelfRenderer");
            if (shelf != null) {
                JSONObject desc = shelf.optJSONObject("description");
                if (desc != null) {
                    JSONArray runs = desc.optJSONArray("runs");
                    if (runs != null && runs.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < runs.length(); i++) {
                            JSONObject run = runs.optJSONObject(i);
                            if (run != null) {
                                sb.append(run.optString("text", ""));
                            }
                        }
                        String fullText = sb.toString();
                        String[] rawLines = fullText.split("\\r?\\n");
                        List<LyricsLine> lines = new ArrayList<>(rawLines.length);
                        for (String l : rawLines) {
                            String trimmed = l.trim();
                            if (!trimmed.isEmpty()) {
                                lines.add(new LyricsLine(LyricsLine.NO_TIME, trimmed));
                            }
                        }
                        if (!lines.isEmpty()) {
                            String sourceName = "YouTube (LyricFind)";
                            JSONObject footer = shelf.optJSONObject("footer");
                            if (footer != null) {
                                JSONArray footerRuns = footer.optJSONArray("runs");
                                if (footerRuns != null && footerRuns.length() > 0) {
                                    String footerText = footerRuns.optJSONObject(0).optString("text", "");
                                    if (!footerText.isEmpty()) {
                                        sourceName = footerText;
                                    }
                                }
                            }
                            return new Lyrics(lines, sourceName, false);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "YouTubeMusicProvider InnerTube browse error", ex);
        }
        return null;
    }

    @Nullable
    private Lyrics fetchFromVideoCaptions(String videoId) {
        try {
            JSONObject playerPayload = new JSONObject();
            playerPayload.put("videoId", videoId);
            JSONObject context = new JSONObject();
            JSONObject client = new JSONObject();
            client.put("clientName", "ANDROID");
            client.put("clientVersion", "19.29.35");
            client.put("hl", "ja");
            context.put("client", client);
            playerPayload.put("context", context);

            JSONObject playerResponse = postJson(PLAYER_URL, playerPayload);
            if (playerResponse == null) {
                return null;
            }

            JSONObject captions = playerResponse.optJSONObject("captions");
            if (captions == null) {
                return null;
            }

            JSONObject tracklist = captions.optJSONObject("playerCaptionsTracklistRenderer");
            if (tracklist == null) {
                return null;
            }

            JSONArray captionTracks = tracklist.optJSONArray("captionTracks");
            if (captionTracks == null || captionTracks.length() == 0) {
                return null;
            }

            // Prefer Japanese captions if available, otherwise first available
            JSONObject targetTrack = null;
            for (int i = 0; i < captionTracks.length(); i++) {
                JSONObject trackObj = captionTracks.optJSONObject(i);
                if (trackObj != null && trackObj.optString("languageCode", "").startsWith("ja")) {
                    targetTrack = trackObj;
                    break;
                }
            }
            if (targetTrack == null) {
                targetTrack = captionTracks.optJSONObject(0);
            }
            if (targetTrack == null) {
                return null;
            }

            String baseUrl = targetTrack.optString("baseUrl", "");
            if (baseUrl.isEmpty()) {
                return null;
            }

            HttpURLConnection conn = LyricsRequests.openConnection(baseUrl);
            String xmlContent = Requester.parseStringAndDisconnect(conn);
            if (xmlContent == null || xmlContent.isEmpty()) {
                return null;
            }

            Matcher matcher = CAPTION_PATTERN.matcher(xmlContent);
            List<LyricsLine> lines = new ArrayList<>();
            while (matcher.find()) {
                String startStr = matcher.group(1);
                String text = unescapeHtml(matcher.group(2)).trim();
                if (!text.isEmpty()) {
                    long startMs = (long) (Double.parseDouble(startStr) * 1000);
                    lines.add(new LyricsLine(startMs, text));
                }
            }

            if (!lines.isEmpty()) {
                return new Lyrics(lines, "YouTube Captions", true);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "YouTubeMusicProvider captions error", ex);
        }
        return null;
    }

    @Nullable
    private String searchVideoId(TrackInfo track) {
        try {
            JSONObject searchPayload = new JSONObject();
            searchPayload.put("query", track.artist() + " " + track.title());
            JSONObject context = new JSONObject();
            JSONObject client = new JSONObject();
            client.put("clientName", "WEB_REMIX");
            client.put("clientVersion", "1.20240826.01.00");
            client.put("hl", "ja");
            context.put("client", client);
            searchPayload.put("context", context);

            JSONObject response = postJson(SEARCH_URL, searchPayload);
            if (response == null) {
                return null;
            }

            Matcher matcher = VIDEO_ID_PATTERN.matcher(response.toString());
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "YouTubeMusicProvider searchVideoId error", ex);
        }
        return null;
    }

    @Nullable
    private static String extractLyricsBrowseId(JSONObject nextResponse) {
        try {
            JSONObject contents = nextResponse.optJSONObject("contents");
            if (contents == null) return null;
            JSONObject singleColumn = contents.optJSONObject("singleColumnMusicWatchNextResultsRenderer");
            if (singleColumn == null) return null;
            JSONObject tabbed = singleColumn.optJSONObject("tabbedRenderer");
            if (tabbed == null) return null;
            JSONObject watchNextTabbed = tabbed.optJSONObject("watchNextTabbedResultsRenderer");
            if (watchNextTabbed == null) return null;
            JSONArray tabs = watchNextTabbed.optJSONArray("tabs");
            if (tabs == null) return null;

            for (int i = 0; i < tabs.length(); i++) {
                JSONObject tab = tabs.optJSONObject(i);
                if (tab == null) continue;
                JSONObject tabRenderer = tab.optJSONObject("tabRenderer");
                if (tabRenderer == null) continue;
                JSONObject endpoint = tabRenderer.optJSONObject("endpoint");
                if (endpoint == null) continue;
                JSONObject browseEndpoint = endpoint.optJSONObject("browseEndpoint");
                if (browseEndpoint != null) {
                    String browseId = browseEndpoint.optString("browseId", "");
                    if (browseId.startsWith("MPLY")) {
                        return browseId;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Nullable
    private static JSONObject postJson(String urlString, JSONObject payload) {
        HttpURLConnection conn = null;
        try {
            conn = Requester.openConnection(urlString);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "com.google.android.apps.youtube.music/6.43.52 (Linux; U; Android 14)");
            conn.setDoOutput(true);

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            if (conn.getResponseCode() == 200) {
                return Requester.parseJSONObject(conn);
            } else {
                LyricsRequests.logFailure("YouTubeMusicProvider", conn);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "postJson failed: " + urlString, ex);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    @Nullable
    private static JSONObject findObject(JSONObject obj, String targetKey) {
        if (obj == null) return null;
        if (obj.has(targetKey)) {
            JSONObject direct = obj.optJSONObject(targetKey);
            if (direct != null) return direct;
        }
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object child = obj.opt(key);
            if (child instanceof JSONObject childObj) {
                JSONObject res = findObject(childObj, targetKey);
                if (res != null) return res;
            } else if (child instanceof JSONArray childArr) {
                for (int i = 0; i < childArr.length(); i++) {
                    Object item = childArr.opt(i);
                    if (item instanceof JSONObject itemObj) {
                        JSONObject res = findObject(itemObj, targetKey);
                        if (res != null) return res;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static JSONArray findArray(JSONObject obj, String targetKey) {
        if (obj == null) return null;
        if (obj.has(targetKey)) {
            JSONArray direct = obj.optJSONArray(targetKey);
            if (direct != null) return direct;
        }
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object child = obj.opt(key);
            if (child instanceof JSONObject childObj) {
                JSONArray res = findArray(childObj, targetKey);
                if (res != null) return res;
            } else if (child instanceof JSONArray childArr) {
                for (int i = 0; i < childArr.length(); i++) {
                    Object item = childArr.opt(i);
                    if (item instanceof JSONObject itemObj) {
                        JSONArray res = findArray(itemObj, targetKey);
                        if (res != null) return res;
                    }
                }
            }
        }
        return null;
    }

    private static String unescapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
