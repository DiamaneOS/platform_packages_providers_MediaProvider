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

import java.io.IOException;

/**
 * The decode service could not do the work now. The caller shows a placeholder or stores no value and asks again
 * later. It never decodes the file itself.
 */
public class VmDecodeUnavailableException extends IOException {
    /**
     * Starts every message, so a process that gets the failure through a provider call (as a FileNotFoundException
     * with this message, the picker's thumbnail from MediaProvider) still knows it is worth asking again.
     */
    public static final String PREFIX = "decode service unavailable: ";

    public VmDecodeUnavailableException(String message) {
        super(PREFIX + message);
    }

    public VmDecodeUnavailableException(String message, Throwable cause) {
        super(PREFIX + message, cause);
    }

    /** True when {@code e} is this failure, here or passed on by a provider call. */
    public static boolean isUnavailable(IOException e) {
        return e instanceof VmDecodeUnavailableException
                || (e != null && e.getMessage() != null && e.getMessage().contains(PREFIX));
    }
}
