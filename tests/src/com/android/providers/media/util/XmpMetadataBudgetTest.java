/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class XmpMetadataBudgetTest {
    @Test
    public void inputRejectedBeforeParserConstruction() {
        assertThrows(XmlPullParserException.class,
                () -> new XmpMetadataBudget(new byte[1024 * 1024 + 1]));
    }

    @Test
    public void depthAndEventCountShareTheFileBudget() throws Exception {
        final XmlPullParser parser = mock(XmlPullParser.class);
        final XmpMetadataBudget budget = new XmpMetadataBudget(new byte[1024]);
        when(parser.getDepth()).thenReturn(65);
        assertThrows(IOException.class, () -> budget.next(parser));
        final XmpMetadataBudget events = new XmpMetadataBudget(new byte[1024]);
        when(parser.getDepth()).thenReturn(1);
        for (int i = 0; i < MetadataBudget.MAX_STEPS; ++i) events.next(parser);
        assertThrows(IOException.class, () -> events.next(parser));
    }

    @Test
    public void attributesAndDtdAreBoundedBeforeCreatingTheParser() {
        assertThrows(XmlPullParserException.class, () -> input("<x" + attributes(129) + "/>"));
        assertThrows(XmlPullParserException.class,
                () -> input("<!DOCTYPE x [<!ATTLIST x a CDATA 'v'>]><x/>"));
        assertThrows(XmlPullParserException.class,
                () -> input("<?xml version='1.0'" + attributes(129) + "?><x/>"));
        final StringBuilder many = new StringBuilder("<root>");
        for (int i = 0; i < 200; ++i) many.append("<x").append(attributes(100)).append("/>");
        many.append("</root>");
        assertThrows(XmlPullParserException.class, () -> input(many.toString()));
    }

    @Test
    public void quotedTextCommentsAndInstructionsKeepTheirMeaning() throws Exception {
        input("<x" + attributes(128) + "/>");
        input("<!-- <!DOCTYPE x> --><x a='= >'><![CDATA[<!DOCTYPE text>]]></x>");
        input("<?proof <!DOCTYPE is text>?><x/>");
        input("<?xml version='1.0' encoding='UTF-8'?><x/>");
        input("<root><x / ><x / ></root>");
    }

    private static XmpMetadataBudget input(String xml) throws XmlPullParserException {
        return new XmpMetadataBudget(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String attributes(int count) {
        final StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; ++i) result.append(" a").append(i).append("='v'");
        return result.toString();
    }
}
