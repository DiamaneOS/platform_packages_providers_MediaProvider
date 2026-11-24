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
 * The file cannot be decoded: an unsupported format, a broken or hostile file, a reply that broke the contract, or a
 * source that could not be read in time. Treated like a file that has no thumbnail. Only the VM's own verdicts
 * ({@link #verdict}) give the same answer when asked again. A read problem may pass.
 */
public class VmDecodeRejectedException extends IOException {
    /** The decode service's verdict on the file (invalid, unsupported, or a reply that broke the contract). */
    public final boolean verdict;

    public VmDecodeRejectedException(String message) {
        this(message, false);
    }

    public VmDecodeRejectedException(String message, boolean verdict) {
        super(message);
        this.verdict = verdict;
    }
}
