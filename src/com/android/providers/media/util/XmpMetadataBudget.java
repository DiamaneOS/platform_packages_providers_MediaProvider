/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.media.util;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.IOException;

/** Bounds XML input and parser events before fields reach the scanner. */
final class XmpMetadataBudget {
    static final int MAX_ATTRIBUTES = 128;
    private final MetadataBudget mBudget = new MetadataBudget();

    XmpMetadataBudget(byte[] raw) throws XmlPullParserException {
        if (raw == null || raw.length > IsoInterface.MAX_XMP_SIZE_BYTES) {
            throw new XmlPullParserException("XMP input limit");
        }
        preflight(raw);
    }

    int next(XmlPullParser parser) throws XmlPullParserException, IOException {
        final int result = parser.next();
        mBudget.visit(parser.getDepth());
        return result;
    }

    // The platform builds a whole attribute list before returning a parser event.
    // Its DTD default attributes also run before that event, even with entities disabled.
    private void preflight(byte[] raw) throws XmlPullParserException {
        int depth = 0;
        for (int i = 0; i < raw.length; ++i) {
            if (raw[i] != '<') continue;
            if (starts(raw, i, "<!--")) {
                i = after(raw, i + 4, "-->") - 1;
                continue;
            }
            if (starts(raw, i, "<![CDATA[")) {
                i = after(raw, i + 9, "]]>") - 1;
                continue;
            }
            final boolean instruction = starts(raw, i, "<?");
            final boolean declaration = instruction && starts(raw, i, "<?xml")
                    && i + 5 < raw.length && (space(raw[i + 5]) || raw[i + 5] == '?');
            if (instruction && !declaration) {
                i = after(raw, i + 2, "?>") - 1;
                continue;
            }
            if (i + 1 >= raw.length || raw[i + 1] == '!') {
                throw new XmlPullParserException("XMP declaration refused");
            }
            final boolean closing = raw[i + 1] == '/';
            if (closing && depth == 0) {
                throw new XmlPullParserException("XMP tag structure");
            }
            if (!declaration && !closing) ++depth;
            visit(depth);
            int attributes = 0;
            byte quote = 0;
            byte last = 0;
            boolean ended = false;
            for (int j = i + 1; j < raw.length; ++j) {
                final byte value = raw[j];
                if (quote != 0) {
                    if (value == quote) {
                        quote = 0;
                        last = value;
                    }
                    continue;
                }
                if (value == '\'' || value == '"') {
                    quote = value;
                    last = value;
                } else if (value == '=') {
                    if (++attributes > MAX_ATTRIBUTES) {
                        throw new XmlPullParserException("XMP attribute limit");
                    }
                    visit(depth);
                    last = value;
                } else if (value == '<') {
                    throw new XmlPullParserException("XMP tag structure");
                } else if (value == '>') {
                    if (!declaration && (closing || last == '/')) --depth;
                    i = j;
                    ended = true;
                    break;
                } else if (!space(value)) {
                    last = value;
                }
            }
            if (!ended) throw new XmlPullParserException("Unclosed XMP tag");
        }
    }

    private void visit(int depth) throws XmlPullParserException {
        try {
            mBudget.visit(depth);
        } catch (IOException e) {
            throw new XmlPullParserException("XMP structure limit", null, e);
        }
    }

    private static boolean space(byte value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r';
    }

    private static boolean starts(byte[] raw, int at, String text) {
        if (at > raw.length - text.length()) return false;
        for (int i = 0; i < text.length(); ++i) {
            if (raw[at + i] != text.charAt(i)) return false;
        }
        return true;
    }

    private static int after(byte[] raw, int at, String end) throws XmlPullParserException {
        for (int i = at; i <= raw.length - end.length(); ++i) {
            if (starts(raw, i, end)) return i + end.length();
        }
        throw new XmlPullParserException("Unclosed XMP markup");
    }
}
