package com.sashank.map_shortest_path_finder.intent;

import java.util.regex.Pattern;

/**
 * Removes precise location data and personal identifiers from user text BEFORE it is sent to
 * the LLM provider. The model only needs place NAMES and preferences; coordinates, links,
 * e-mail addresses and phone numbers are never needed (coordinates for the start point travel in
 * a separate structured request field that is never sent to the model at all).
 *
 * Place names themselves must be sent — they are the input — so this does not claim to anonymise
 * free text, only to strip the machine-precise and directly identifying items.
 *
 * Also neutralises the prompt's own delimiter tags so user text can't close the data block.
 */
final class QueryRedactor {

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern COORDINATE_PAIR =
        Pattern.compile("[-+]?\\d{1,3}\\.\\d{3,}\\s*[,;/]?\\s*[-+]?\\d{1,3}\\.\\d{3,}");
    /** A single precise decimal (4+ fractional digits) is almost certainly a latitude or longitude. */
    private static final Pattern PRECISE_DECIMAL = Pattern.compile("[-+]?\\d+\\.\\d{4,}");
    private static final Pattern PHONE = Pattern.compile("(?<![\\d.])\\+?\\d[\\d\\s().-]{7,}\\d(?![\\d.])");
    private static final Pattern DELIMITER_TAG = Pattern.compile("(?i)</?\\s*user_request\\s*>");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    private QueryRedactor() {}

    static String redact(String text) {
        String t = CONTROL.matcher(text).replaceAll(" ");
        t = DELIMITER_TAG.matcher(t).replaceAll(" ");
        t = URL.matcher(t).replaceAll("[link removed]");
        t = EMAIL.matcher(t).replaceAll("[email removed]");
        t = COORDINATE_PAIR.matcher(t).replaceAll("[coordinates removed]");
        t = PRECISE_DECIMAL.matcher(t).replaceAll("[coordinates removed]");
        t = PHONE.matcher(t).replaceAll("[number removed]");
        return t.strip();
    }
}
