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
 * The file cannot be decoded: an unsupported format, a broken or hostile file, or a reply that broke the contract.
 * Treated like a file that has no thumbnail. Asking again would give the same answer.
 */
public class VmDecodeRejectedException extends IOException {
    public VmDecodeRejectedException(String message) {
        super(message);
    }
}
