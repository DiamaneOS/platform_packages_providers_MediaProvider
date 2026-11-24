/*
 * Copyright (C) 2026 DiamaneOS
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
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import org.junit.Test;

public class SourceBudgetTest {
    @Test
    public void admissionCountsActiveWorkAndBytes() {
        final SourceBudget budget = new SourceBudget();
        final SourceBudget.Ticket first = budget.reserve("app", 32L * 1024 * 1024);
        final SourceBudget.Ticket second = budget.reserve("app", 32L * 1024 * 1024);
        assertNotNull(first);
        assertNotNull(second);
        assertNull(budget.reserve("app", 1));
        assertNotNull(budget.reserve("other", 64L * 1024 * 1024));
        assertNull(budget.reserve("third", 1));
        first.close();
        first.close();
        assertNotNull(budget.reserve("app", 1));
        second.close();
    }

    @Test
    public void negativeReservationsFailClosed() {
        assertNull(new SourceBudget().reserve("app", -1));
    }
}
